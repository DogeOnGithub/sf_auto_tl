"""LLM 客户端，调用 OpenAI 兼容接口批量翻译 String_Record。

<p>凭证来源被抽象成 CredentialSource（见 llm_pool）：用户自带 KEY 走 FixedSource，
未自带则走默认凭证池。本模块只负责分批、重试与错误处置，不再自己解析凭证。
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from typing import Callable, Optional

from engine.esm_parser import StringRecord
from engine.llm_config import (
    DEFAULT_MAX_BATCH_CHARS,
    DEFAULT_MAX_BATCH_RECORDS,
    MAX_OUTPUT_TOKENS,
    MAX_PROMPT_DICT_ENTRIES,
    MAX_RETRIES,
    MAX_SPLIT_DEPTH,
    MIN_BATCH_COVERAGE,
    POOL_MAX_MEMBER_SWITCHES,
    REQUEST_TIMEOUT,
    RETRY_DELAYS,
)
from engine.llm_pool import (
    ERROR_KIND_BAD_REQUEST,
    ERROR_KIND_TRANSIENT,
    FixedSource,
    PoolMember,
    classify_error,
    get_pool,
    normalize_base_url,  # noqa: F401 保留再导出 历史调用方从本模块引用该函数
)
from engine.prompt_builder import build_prompt

logger = logging.getLogger(__name__)

# 匹配 <...> 标签的正则
_TAG_PATTERN = re.compile(r"<[^>]+>")

# 匹配 [编号] 译文 行首的正则
_NUMBERED_LINE_PATTERN = re.compile(r"\[(\d+)\]\s*(.*)")


@dataclass
class UsageTotals:
    """一个任务累计的 token 用量。

    单批用量打 DEBUG、任务结束汇总一条日志，避免 3000+ 批次刷屏的同时
    仍然能在额度异常时倒推是哪个任务、花在推理还是译文上。
    """

    requests: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    reasoning_tokens: int = 0


@dataclass
class SplitBudget:
    """一个任务允许的额外拆分请求总数。

    截断拆分是递归的，单批最坏会放大到 31 次请求（1+2+4+8+16）。模型系统性
    返回空正文时（模型名写错、推理模型思维链吃光输出预算）每一批都会走满这个
    放大系数，把配额烧光却零产出。用一个任务级预算把总放大倍数封住。
    """

    remaining: int

    def take(self) -> bool:
        """扣减一次拆分额度。

        Returns:
            额度充足返回 True 已耗尽返回 False。
        """
        if self.remaining <= 0:
            return False
        self.remaining -= 1
        return True


def _completion_kwargs() -> dict:
    """组装可选的 chat.completions 参数。

    只在显式配置 LLM_MAX_OUTPUT_TOKENS 时才下发 max_tokens。默认不下发是因为
    各家 provider 的允许值差异很大，传一个超限值会直接 400，代价是整批词条回退原文。

    Returns:
        可直接展开进 create() 的参数字典 未配置时为空。
    """
    if MAX_OUTPUT_TOKENS is None:
        return {}
    return {"max_tokens": MAX_OUTPUT_TOKENS}


def _resolve_source(
    llm_base_url: str | None,
    llm_api_key: str | None,
    llm_model: str | None,
):
    """解析本次调用使用的凭证来源。

    <p>三项齐全视为用户自带凭证，直接构造 FixedSource：不入池、不 failover、不计入统计。
    要求三项都有而不是只看地址和 Key，是因为缺了模型名就只能猜一个默认值，
    而猜错的模型名在对方那里就是 404，最后表现为「配了自己的 KEY 却一条都没翻出来」。

    <p>未自带则走默认凭证池。池为空时返回 None，由调用方判失败——不再回退到内置 KEY，
    否则「配置漏了」会表现成「悄悄花了别的钱」。

    Args:
        llm_base_url: 调用方传入的 LLM API 地址。
        llm_api_key: 调用方传入的 LLM API Key。
        llm_model: 调用方传入的模型名称。

    Returns:
        FixedSource 或默认凭证池，两者都不可用时为 None。
    """
    parts = [
        (llm_base_url or "").strip(),
        (llm_api_key or "").strip(),
        (llm_model or "").strip(),
    ]
    if all(parts):
        logger.info("[_resolve_source] 使用调用方自带凭证 model %s", parts[2])
        return FixedSource(base_url=parts[0], api_key=parts[1], model=parts[2])
    if any(parts):
        # 只填了一部分：按自带处理会用上兜底的另一半，等于花着池的钱打到用户的地址上
        logger.warning(
            "[_resolve_source] 自带凭证不完整 回落默认凭证池 has_base_url %s has_api_key %s has_model %s",
            bool(parts[0]), bool(parts[1]), bool(parts[2]),
        )

    pool = get_pool()
    pool.refresh()
    if pool.size() == 0:
        logger.error("[_resolve_source] 默认凭证池为空 无可用凭证")
        return None
    logger.info("[_resolve_source] 使用默认凭证池 %s", pool.describe())
    return pool


def _mask_tags(text: str) -> tuple[str, list[str]]:
    """将文本中的 <...> 标签替换为占位符 {{TAG_0}} {{TAG_1}} 等，返回替换后文本和标签列表。"""
    tags = _TAG_PATTERN.findall(text)
    masked = text
    for i, tag in enumerate(tags):
        masked = masked.replace(tag, f"{{{{TAG_{i}}}}}", 1)
    return masked, tags


def _unmask_tags(text: str, tags: list[str]) -> str:
    """将占位符 {{TAG_0}} 等还原为原始 <...> 标签。"""
    result = text
    for i, tag in enumerate(tags):
        result = result.replace(f"{{{{TAG_{i}}}}}", tag)
    return result


def _fix_br_tags(original: str, translated: str) -> str:
    """修复 LLM 错误引入的 <br> 标签：如果原文不含 <br> 但译文含有，还原为原文的换行符。"""
    if "<br>" not in original and "<br>" in translated:
        # 判断原文使用的换行符类型
        newline = "\r\n" if "\r\n" in original else "\n"
        translated = translated.replace("<br>", newline)
    return translated


def _relevant_entries(
    entries: list[dict] | None,
    texts: list[str],
) -> list[dict] | None:
    """只保留本批文本中真实出现的词典条目。

    <p>build_prompt 会把整份词典塞进每个 prompt。批次调小之后批次数涨了一个数量级，
    整份词典的 token 开销会跟着线性放大，而没出现在本批文本里的术语对这批译文
    毫无约束价值。按批过滤掉之后每个 prompt 只带真正相关的术语，成本降下来、
    约束也更聚焦。递归拆分的子批次会自动进一步收窄。

    条数仍超过 MAX_PROMPT_DICT_ENTRIES 时按术语长度降序截断，长术语更易被误译。

    <p>是否命中用忽略大小写判断：术语表由 LLM 生成，它可能把 WeaponEngineering 这类
    写法归一化成 Title Case，精确匹配就会漏掉这条约束，等于这批文本失去了该术语的统一
    译名。实测忽略大小写不会让每批多带术语（专有名词的大小写本来就稳定），是纯收益。
    注意只有「判断是否携带」忽略大小写，下发给 prompt 的仍是词典里原本的写法。

    Args:
        entries: 完整词典条目列表 可为 None。
        texts: 本批待翻译原文列表。

    Returns:
        过滤后的词典条目列表 入参为空时原样返回。
    """
    if not entries:
        return entries

    blob = "\n".join(texts).lower()
    matched = [
        entry for entry in entries
        if entry.get("sourceText") and entry["sourceText"].lower() in blob
    ]

    if len(matched) > MAX_PROMPT_DICT_ENTRIES:
        matched.sort(key=lambda e: len(e["sourceText"]), reverse=True)
        logger.debug(
            "[_relevant_entries] 词典条数超限 已截断 matched %d limit %d",
            len(matched), MAX_PROMPT_DICT_ENTRIES,
        )
        matched = matched[:MAX_PROMPT_DICT_ENTRIES]

    logger.debug(
        "[_relevant_entries] 词典按批过滤 total %d matched %d batch_size %d",
        len(entries), len(matched), len(texts),
    )
    return matched


def _parse_response(
    response_text: str,
    records: list[StringRecord],
) -> tuple[dict[str, str], list[str]]:
    """解析 LLM 返回的翻译文本，按编号与原始记录 ID 匹配。

    LLM 返回格式为 [编号] 译文，按编号匹配对应的原始记录。

    <p>这里刻意不给缺失词条回退原文：回退会让「翻到了」和「没翻到」在返回值里
    无法区分，上层既判断不出真实产出（全批失败的任务因此显示为翻译完成），
    也没法据此告警。回退统一由 translator 在合并阶段做。

    Args:
        response_text: LLM 返回的翻译文本。
        records: 原始 StringRecord 列表。

    Returns:
        (record_id -> translated_text 的映射, 无译文的 record_id 列表)。
        映射中只包含真正拿到译文的记录。
    """
    translations = _parse_numbered_lines(response_text)

    result: dict[str, str] = {}
    missing: list[str] = []

    for i, record in enumerate(records):
        translated = translations.get(i + 1, "")
        if translated:
            result[record.record_id] = translated
        else:
            missing.append(record.record_id)

        # 逐条原文译文用 DEBUG：32 万词条的任务在 INFO 级会打出 32 万行含全文的日志
        logger.debug(
            "[_parse_response] record_id %s 原文 %s 译文 %s",
            record.record_id, record.text, translated,
        )

    # 汇总成一条告警 而不是每条缺失打一行 便于从日志里看出截断规模
    if missing:
        logger.warning(
            "[_parse_response] 部分词条无译文 missing_count %d batch_size %d sample %s",
            len(missing), len(records), missing[:5],
        )

    return result, missing


def _parse_numbered_lines(response_text: str) -> dict[int, str]:
    """把 LLM 返回的 [编号] 译文 文本解析为 编号 -> 译文 映射。

    支持译文跨多行：下一个 [编号] 出现前的所有行都归属当前编号。
    空译文不计入结果，便于调用方用 len() 判断实际覆盖率。

    Args:
        response_text: LLM 返回的原始文本。

    Returns:
        编号 -> 非空译文 的映射。
    """
    translations: dict[int, str] = {}
    current_idx: int | None = None
    current_lines: list[str] = []

    for line in response_text.strip().split("\n"):
        match = _NUMBERED_LINE_PATTERN.match(line)
        if match:
            # 保存上一条
            if current_idx is not None:
                translations[current_idx] = "\n".join(current_lines).strip()
            current_idx = int(match.group(1))
            current_lines = [match.group(2)]
        elif current_idx is not None:
            current_lines.append(line)

    # 保存最后一条
    if current_idx is not None:
        translations[current_idx] = "\n".join(current_lines).strip()

    return {idx: text for idx, text in translations.items() if text}


def _accumulate_usage(
    response,
    records: list[StringRecord],
    depth: int,
    usage: UsageTotals | None,
) -> tuple[int, int, int]:
    """累计单次调用的 token 用量。

    <p>之前完全没有记录用量，导致额度被烧完之后无法从日志倒推是哪些任务、花在哪里，
    只能靠词条数反推。推理模型的思维链计入 completion_tokens，这里单独累计便于识别。

    <p>同时把用量返回给调用方：池化之后成本要按成员归集，成员维度的累加发生在
    CredentialSource 上，而任务维度的累加仍然留在 UsageTotals 里。

    Args:
        response: LLM 响应对象。
        records: 本批次记录列表。
        depth: 当前拆分深度。
        usage: 任务级累计器 为 None 时只打 DEBUG 不累计。

    Returns:
        (prompt_tokens, completion_tokens, reasoning_tokens)。响应未带用量信息时全为 0。
    """
    raw = getattr(response, "usage", None)
    if raw is None:
        return 0, 0, 0
    prompt_tokens = getattr(raw, "prompt_tokens", None)
    completion_tokens = getattr(raw, "completion_tokens", None)
    if not isinstance(prompt_tokens, int) or not isinstance(completion_tokens, int):
        return 0, 0, 0

    # 推理模型把思维链算在 completion 里 单独取出来便于判断钱花在推理还是译文上
    details = getattr(raw, "completion_tokens_details", None)
    reasoning_tokens = getattr(details, "reasoning_tokens", None) if details else None
    if not isinstance(reasoning_tokens, int):
        reasoning_tokens = 0

    if usage is not None:
        usage.requests += 1
        usage.prompt_tokens += prompt_tokens
        usage.completion_tokens += completion_tokens
        usage.reasoning_tokens += reasoning_tokens

    logger.debug(
        "[_accumulate_usage] 单批用量 batch_size %d depth %d prompt_tokens %d completion_tokens %d reasoning_tokens %d",
        len(records), depth, prompt_tokens, completion_tokens, reasoning_tokens,
    )
    return prompt_tokens, completion_tokens, reasoning_tokens


def _should_split(
    choice,
    response_text: str,
    records: list[StringRecord],
    depth: int,
    budget: SplitBudget | None,
) -> bool:
    """判断响应是否不完整、需要把批次对半拆开重试。

    <p>三种不完整信号：finish_reason 为 length（撞到输出上限）、正文为空
    （推理模型的思维链吃光了输出预算）、译文覆盖率低于阈值（响应中途被截断）。

    Args:
        choice: LLM 响应的首个 choice 对象。
        response_text: choice 中的正文内容。
        records: 本批次的记录列表。
        depth: 当前拆分深度。
        budget: 任务级拆分预算 为 None 时不限制（仅直接调用 _translate_batch 的场景）。

    Returns:
        是否需要拆分重试。
    """
    if len(records) <= 1 or depth >= MAX_SPLIT_DEPTH:
        return False

    empty = not response_text.strip()
    truncated = getattr(choice, "finish_reason", None) == "length"
    coverage = 0.0 if empty else len(_parse_numbered_lines(response_text)) / len(records)

    if not empty and not truncated and coverage >= MIN_BATCH_COVERAGE:
        return False

    # 预算耗尽说明这个任务已经反复拆分过 大概率是模型或配置本身有问题
    # 继续放大只会烧配额 这里停手 让缺失词条走上层的原文回退和告警
    if budget is not None and not budget.take():
        logger.warning(
            "[_should_split] 拆分预算已耗尽 放弃拆分 batch_size %d depth %d coverage %.2f",
            len(records), depth, coverage,
        )
        return False

    logger.warning(
        "[_should_split] 响应不完整 拆分重试 batch_size %d depth %d empty %s coverage %.2f finish_reason %s",
        len(records), depth, empty, coverage, getattr(choice, "finish_reason", None),
    )
    return True


def _split_and_translate(
    source,
    records: list[StringRecord],
    target_lang: str,
    custom_prompt: str | None,
    dictionary_entries: list[dict] | None,
    depth: int,
    budget: SplitBudget | None,
    usage: UsageTotals | None,
) -> dict[str, str]:
    """把批次对半拆开分别翻译并合并结果。

    调用方已保证 len(records) >= 2，因此每次拆分都能真正缩小批次不会死循环。

    Args:
        source: 凭证来源 调用方已钉在触发拆分的那个成员上。
        records: 待拆分的记录列表。
        target_lang: 目标语言。
        custom_prompt: 用户自定义 Prompt。
        dictionary_entries: 词典词条列表。
        depth: 当前拆分深度 传给子批次时加一。
        budget: 任务级拆分预算。
        usage: 任务级 token 用量累计器。

    Returns:
        record_id -> translated_text 的合并映射。
    """
    mid = len(records) // 2
    merged: dict[str, str] = {}
    for half in (records[:mid], records[mid:]):
        if not half:
            continue
        merged.update(_translate_batch(
            source,
            half,
            target_lang,
            custom_prompt,
            dictionary_entries,
            depth + 1,
            budget,
            usage,
        ))
    return merged


def _translate_batch(
    source,
    records: list[StringRecord],
    target_lang: str,
    custom_prompt: str | None,
    dictionary_entries: list[dict] | None,
    depth: int = 0,
    budget: SplitBudget | None = None,
    usage: UsageTotals | None = None,
) -> dict[str, str]:
    """翻译单个批次的记录，包含错误分类、成员切换与截断自动拆分。

    <p>响应不完整（finish_reason 为 length、正文为空、或译文覆盖率低于
    MIN_BATCH_COVERAGE）时把批次对半拆开重试，而不是让缺失的词条静默丢失。
    拆分会放大请求数（单批最坏 31 次），所以由 SplitBudget 在任务级封顶。

    <p>失败处置按错误类型分流，这是池化的核心：限流、鉴权失效、余额不足、模型名不存在
    都是成员级问题，直接换成员而不睡等；网络抖动和 5xx 才在同一成员上退避重试。
    改造前所有非 400 错误都退避重试同一把 KEY，一个失效的 Key 会把每批的重试预算耗光。

    <p>单批总请求数被封在 MAX_RETRIES + POOL_MAX_MEMBER_SWITCHES：切换和重试是两个
    独立维度，不封顶的话三成员池会把 3 次重试放大成 9 次付费请求。

    Args:
        source: 凭证来源，FixedSource（自带 KEY）或默认凭证池。
        records: 待翻译的 StringRecord 列表。
        target_lang: 目标语言。
        custom_prompt: 用户自定义 Prompt。
        dictionary_entries: 词典词条列表。
        depth: 当前拆分深度 由递归调用维护 达到 MAX_SPLIT_DEPTH 后不再拆分。
        budget: 任务级拆分预算 由 translate_records 创建。
        usage: 任务级 token 用量累计器 由 translate_records 创建。

    Returns:
        record_id -> translated_text 的映射字典 只包含真正拿到译文的记录。

    Raises:
        无异常抛出，失败时返回空字典并记录错误日志。
    """
    texts = [r.text for r in records]

    # 遮蔽 <...> 标签，防止 LLM 翻译标签内容
    masked_texts = []
    tags_map = []  # 每条文本对应的标签列表
    for text in texts:
        masked, tags = _mask_tags(text)
        masked_texts.append(masked)
        tags_map.append(tags)

    prompt = build_prompt(
        texts_to_translate=masked_texts,
        custom_prompt=custom_prompt,
        dictionary_entries=_relevant_entries(dictionary_entries, texts),
    )

    system_message = f"You are a professional game localization translator. Translate the text to {target_lang}."

    member = source.acquire()
    if member is None:
        logger.error(
            "[_translate_batch] 无可用 LLM 凭证 批次标记为失败 records_count %d",
            len(records),
        )
        return {}
    client = source.client_for(member)

    tried: set = set()
    switches = 0
    transient_attempts = 0
    total_attempts = 0
    max_total_attempts = MAX_RETRIES + POOL_MAX_MEMBER_SWITCHES

    def next_member(current: PoolMember) -> Optional[PoolMember]:
        """换一个本批次还没试过的成员，不支持切换或已无成员可换时返回 None。

        <p>supports_failover 的判断收在这里而不是散在每个分支：自带凭证与钉住成员的来源
        都会无条件返回同一个成员，漏判就会「切换」到自己身上，把重试次数悄悄放大一倍。
        """
        if not source.supports_failover():
            return None
        tried.add(current.member_id)
        return source.acquire(exclude=tried)

    while total_attempts < max_total_attempts:
        total_attempts += 1
        try:
            response = client.chat.completions.create(
                model=member.model,
                messages=[
                    {"role": "system", "content": system_message},
                    {"role": "user", "content": prompt},
                ],
                timeout=REQUEST_TIMEOUT,
                **_completion_kwargs(),
            )
            choice = response.choices[0]
            response_text = choice.message.content or ""
            prompt_tokens, completion_tokens, reasoning_tokens = _accumulate_usage(
                response, records, depth, usage,
            )
            source.record_success(member, prompt_tokens, completion_tokens, reasoning_tokens)

            # 响应不完整时对半拆分重试 避免整批词条丢失
            if _should_split(choice, response_text, records, depth, budget):
                # 钉在同一成员上：拆分决策是按它的输出上限做的，换成员会让判断失效
                return _split_and_translate(
                    source=source.pinned(member),
                    records=records,
                    target_lang=target_lang,
                    custom_prompt=custom_prompt,
                    dictionary_entries=dictionary_entries,
                    depth=depth,
                    budget=budget,
                    usage=usage,
                )

            result, _ = _parse_response(response_text, records)
            # 还原标签占位符
            for i, record in enumerate(records):
                if record.record_id in result and tags_map[i]:
                    result[record.record_id] = _unmask_tags(result[record.record_id], tags_map[i])
            # 修复 LLM 错误引入的 <br> 标签
            for record in records:
                if record.record_id in result:
                    result[record.record_id] = _fix_br_tags(record.text, result[record.record_id])
            return result

        except Exception as e:
            kind = classify_error(e)
            source.record_failure(member, kind, str(e))

            # 自带凭证没有成员可换：把非 400 一律按瞬时错误退避重试，
            # 与池化改造前的行为保持一致，不让用户的任务因为一次限流就直接失败
            if not source.supports_failover() and kind != ERROR_KIND_BAD_REQUEST:
                kind = ERROR_KIND_TRANSIENT

            if kind == ERROR_KIND_BAD_REQUEST:
                # 400 多数是参数或内容问题（prompt 触发内容过滤、max_tokens 超限），重试不会变好。
                # 但也可能是这个成员的模型名不存在——部分供应商对未知模型返回 400 而不是 404，
                # 所以给一次换成员的机会来区分二者，代价封在一次额外请求
                candidate = next_member(member) if switches < 1 else None
                if candidate is not None:
                    member = candidate
                    client = source.client_for(member)
                    switches += 1
                    logger.warning(
                        "[_translate_batch] 请求被拒绝 换一个成员确认是否为成员配置问题 member %s records_count %d error %s",
                        member.name, len(records), str(e),
                    )
                    continue
                logger.error(
                    "[_translate_batch] 请求被拒绝 不重试 records_count %d model %s error %s",
                    len(records), member.model, str(e),
                )
                break

            if kind == ERROR_KIND_TRANSIENT:
                transient_attempts += 1
                if transient_attempts < MAX_RETRIES and total_attempts < max_total_attempts:
                    delay = RETRY_DELAYS[min(transient_attempts - 1, len(RETRY_DELAYS) - 1)]
                    logger.warning(
                        "[_translate_batch] LLM 调用失败 attempt %d/%d delay %ds member %s error %s",
                        transient_attempts, MAX_RETRIES, delay, member.name, str(e),
                    )
                    time.sleep(delay)
                    continue
                candidate = next_member(member) if switches < POOL_MAX_MEMBER_SWITCHES else None
                if candidate is not None:
                    member = candidate
                    client = source.client_for(member)
                    switches += 1
                    transient_attempts = 0
                    logger.warning(
                        "[_translate_batch] 同成员重试耗尽 切换成员继续 member %s records_count %d",
                        member.name, len(records),
                    )
                    continue
                logger.error(
                    "[_translate_batch] LLM 调用重试耗尽 批次标记为失败 records_count %d error %s",
                    len(records), str(e),
                )
                break

            # 限流、鉴权失效、余额不足、模型名不存在：成员级问题，不睡等直接换人
            # 该成员已在 record_failure 里被冷却，后续批次不会再撞上它
            candidate = next_member(member) if switches < POOL_MAX_MEMBER_SWITCHES else None
            if candidate is not None:
                previous = member.name
                member = candidate
                client = source.client_for(member)
                switches += 1
                logger.warning(
                    "[_translate_batch] 成员不可用 切换成员继续 kind %s from %s to %s records_count %d",
                    kind, previous, member.name, len(records),
                )
                continue
            logger.error(
                "[_translate_batch] 成员不可用且无成员可换 批次标记为失败 kind %s records_count %d error %s",
                kind, len(records), str(e),
            )
            break

    return {}


def _split_batches(
    records: list[StringRecord],
    max_chars: int,
    max_records: int,
) -> list[list[StringRecord]]:
    """按文本总字符数和最大记录数动态分批。

    逐条累加字符数 当累计字符数超过 max_chars 或记录数达到 max_records 时切分新批次。
    单条文本超过 max_chars 时独立成一批 不会被跳过。

    Args:
        records: 待分批的记录列表。
        max_chars: 每批文本总字符数上限。
        max_records: 每批最大记录数上限。

    Returns:
        分批后的记录列表。
    """
    batches: list[list[StringRecord]] = []
    current_batch: list[StringRecord] = []
    current_chars = 0

    for record in records:
        text_len = len(record.text)

        # 当前批次加入这条后会超限 先切分
        if current_batch and (current_chars + text_len > max_chars or len(current_batch) >= max_records):
            batches.append(current_batch)
            current_batch = []
            current_chars = 0

        current_batch.append(record)
        current_chars += text_len

    if current_batch:
        batches.append(current_batch)

    return batches


def translate_records(
    records: list[StringRecord],
    target_lang: str = "zh-CN",
    custom_prompt: str | None = None,
    dictionary_entries: list[dict] | None = None,
    batch_size: int = 20,
    max_batch_chars: int = DEFAULT_MAX_BATCH_CHARS,
    max_batch_records: int = DEFAULT_MAX_BATCH_RECORDS,
    on_batch_done: Callable[[int], None] | None = None,
    on_batch_translated: Callable[[dict[str, str], list[StringRecord]], None] | None = None,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
    task_id: str | None = None,
) -> dict[str, str]:
    """批量翻译 StringRecord 列表。

    根据文本总字符数动态分批 充分利用 LLM 上下文窗口。
    每批独立重试 失败的批次记录错误日志 不影响其他批次。

    <p>凭证按批次而不是按任务选取：一个几十万词条的 mod 如果全压在同一个成员上，
    等于没有分散成本，而分散成本正是池化的目的。

    Args:
        records: 待翻译的 StringRecord 列表。
        target_lang: 目标语言 默认 zh-CN。
        custom_prompt: 用户自定义 Prompt None 时使用默认模板。
        dictionary_entries: 词典词条列表 实际下发时按批过滤为本批相关的条目。
        batch_size: 已废弃 保留参数兼容性 实际使用 max_batch_chars 和 max_batch_records。
        max_batch_chars: 每批文本总字符数上限 默认由输出 token 预算反推 见 DEFAULT_MAX_BATCH_CHARS。
        max_batch_records: 每批最大记录数上限 默认取 LLM_MAX_BATCH_RECORDS 环境变量或 80。
        on_batch_done: 每完成一个 Batch 后的回调函数 参数为当前已翻译总数。
        on_batch_translated: 每完成一个 Batch 后的回调函数 参数为该批翻译结果和对应的原始记录。
        llm_base_url: 自定义 LLM API 地址 与 Key、模型名同时提供时走自带凭证。
        llm_api_key: 自定义 LLM API Key。
        llm_model: 自定义 LLM 模型名称。
        task_id: 任务 ID 仅用于用量日志关联。

    Returns:
        record_id -> translated_text 的映射字典。
        只包含真正拿到译文的记录 缺失词条的原文回退由调用方处理。
    """
    if not records:
        logger.info("[translate_records] 无待翻译记录")
        return {}

    # 按文本字符数动态分批
    batches = _split_batches(records, max_batch_chars, max_batch_records)

    logger.info(
        "[translate_records] 开始翻译 task_id %s records_count %d batches_count %d target_lang %s max_batch_chars %d max_batch_records %d",
        task_id, len(records), len(batches), target_lang, max_batch_chars, max_batch_records,
    )

    source = _resolve_source(llm_base_url, llm_api_key, llm_model)
    if source is None:
        logger.error(
            "[translate_records] 无可用 LLM 凭证 放弃翻译 task_id %s records_count %d",
            task_id, len(records),
        )
        return {}

    all_translations: dict[str, str] = {}

    # 拆分额度按批次数的 2 倍给：正常任务只有零星批次需要拆分 用不到；
    # 系统性失败时最多多打 2 倍请求就会停手 不会按 31 倍放大
    budget = SplitBudget(remaining=len(batches) * 2)
    usage = UsageTotals()

    try:
        for batch_num, batch in enumerate(batches, 1):
            batch_chars = sum(len(r.text) for r in batch)
            logger.debug(
                "[translate_records] 翻译批次 %d/%d records_count %d chars %d",
                batch_num, len(batches), len(batch), batch_chars,
            )

            batch_result = _translate_batch(
                source=source,
                records=batch,
                target_lang=target_lang,
                custom_prompt=custom_prompt,
                dictionary_entries=dictionary_entries,
                budget=budget,
                usage=usage,
            )
            all_translations.update(batch_result)

            if on_batch_translated is not None and batch_result:
                on_batch_translated(batch_result, batch)

            if on_batch_done is not None:
                on_batch_done(len(all_translations))
    finally:
        # 任务中途异常也要把已产生的成员用量交出去 否则这部分成本在分散度上不可见
        source.flush()

    logger.info(
        "[translate_records] 翻译完成 task_id %s total %d translated %d missing %d split_budget_left %d",
        task_id, len(records), len(all_translations),
        len(records) - len(all_translations), budget.remaining,
    )
    logger.info(
        "[translate_records] token 用量汇总 task_id %s requests %d prompt_tokens %d completion_tokens %d reasoning_tokens %d",
        task_id, usage.requests, usage.prompt_tokens, usage.completion_tokens, usage.reasoning_tokens,
    )
    return all_translations
