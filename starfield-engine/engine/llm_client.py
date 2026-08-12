"""LLM 客户端，调用 OpenAI 兼容接口批量翻译 String_Record。"""

from __future__ import annotations

import logging
import os
import re
import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Union

from openai import BadRequestError, OpenAI

from engine.esm_parser import StringRecord
from engine.prompt_builder import build_prompt

logger = logging.getLogger(__name__)

# 重试配置
MAX_RETRIES = 3
RETRY_DELAYS = [1, 2, 4]  # 指数退避间隔（秒）


def _env_int(name: str, default: int) -> int:
    """读取正整数型环境变量 非法或非正值回退默认值。

    批次与输出上限做成可配置 是为了线上换模型时不用重新构建镜像就能调参。

    Args:
        name: 环境变量名。
        default: 默认值。

    Returns:
        解析后的正整数。
    """
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        logger.warning("[_env_int] 环境变量非整数 使用默认值 name %s raw %s default %d", name, raw, default)
        return default
    if value <= 0:
        logger.warning("[_env_int] 环境变量必须为正数 使用默认值 name %s raw %s default %d", name, raw, default)
        return default
    return value


def _env_int_or_none(name: str) -> int | None:
    """读取可选的正整数型环境变量 未配置或非法时返回 None。

    用于「不配置就不下发」的参数 与 _env_int 的区别是没有兜底默认值。

    Args:
        name: 环境变量名。

    Returns:
        解析后的正整数 未配置或非法时为 None。
    """
    raw = os.environ.get(name)
    if not raw:
        return None
    try:
        value = int(raw)
    except ValueError:
        logger.warning("[_env_int_or_none] 环境变量非整数 忽略 name %s raw %s", name, raw)
        return None
    if value <= 0:
        logger.warning("[_env_int_or_none] 环境变量必须为正数 忽略 name %s raw %s", name, raw)
        return None
    return value


# 单批输出 token 预算
# 我们不再显式下发 max_tokens（见 _completion_kwargs），实际上限由 provider 决定，
# 而常见 OpenAI 兼容服务的默认输出上限低到 4096。批次按 4096 的八成反推，
# 保证常规批次不会撞上限；万一撞上了还有 _should_split 的拆分兜底。
OUTPUT_TOKEN_BUDGET = _env_int("LLM_OUTPUT_TOKEN_BUDGET", 3200)

# 原文字符数到输出 token 数的折算系数
# 经验值：英文原文约 4 字符 1 token，EN→ZH 译文 token 数约为原文 token 的 1.3 倍，
# 再加每行 [编号] 前缀的开销，合起来 1 字符原文约消耗 0.4 个输出 token。
# 这是估算不是保证，所以只用来定批次上限，真实截断仍靠 finish_reason 判断。
_OUTPUT_TOKENS_PER_SOURCE_CHAR = 0.4

# 分批上限：字符数和记录数是「双重条件」 谁先触顶就切批
# 字符数上限由输出预算反推（3200 / 0.4 = 8000）作为输出预算的安全阀；
# 线上词条平均 73 字符 p90 214 字符 因此 80 条常规情况约 5800 字符 由记录数先触顶；
# 长 DESC 密集的批次则由 8000 字符先触顶。
DEFAULT_MAX_BATCH_CHARS = _env_int(
    "LLM_MAX_BATCH_CHARS", int(OUTPUT_TOKEN_BUDGET / _OUTPUT_TOKENS_PER_SOURCE_CHAR)
)
DEFAULT_MAX_BATCH_RECORDS = _env_int("LLM_MAX_BATCH_RECORDS", 80)

# 单次响应输出 token 上限 未配置时不下发该参数
# 硬编码下发是有害的：上限高于 provider 或模型允许值时会直接 400，
# 而 400 会让整批走完重试后返回空结果、词条静默回退原文。
# 截断检测不依赖这个参数——撞到 provider 自己的上限时 finish_reason 同样是 length。
MAX_OUTPUT_TOKENS = _env_int_or_none("LLM_MAX_OUTPUT_TOKENS")

# 单次 LLM 请求超时（秒）不设置时 SDK 默认 600s 会让卡住的批次拖住整个任务
REQUEST_TIMEOUT = _env_int("LLM_REQUEST_TIMEOUT", 300)

# 检测到截断时对半拆分重试的最大深度 80 条按 2^4 可降到 5 条一批
MAX_SPLIT_DEPTH = _env_int("LLM_MAX_SPLIT_DEPTH", 4)

# 单个 prompt 内最多携带的词典条数 超出时按术语长度降序截断
# 长术语更容易被误译 优先保留
MAX_PROMPT_DICT_ENTRIES = _env_int("LLM_MAX_PROMPT_DICT_ENTRIES", 200)

# 译文覆盖率低于此比例视为响应不完整 触发拆分重试
# 取 0.9 而非 1.0 是容忍模型偶发漏掉个别空文本 不为此付重试成本
MIN_BATCH_COVERAGE = 0.9

# SDK 会自行拼接的端点后缀 出现在 base_url 末尾时属于误填 需去掉
_CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

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


def normalize_base_url(base_url: str | None) -> str | None:
    """规整 LLM base_url 去掉误填的 /chat/completions 端点后缀。

    <p>OpenAI SDK 会在 base_url 后面自己拼 /chat/completions。用户从供应商文档里复制
    完整端点地址填进来时，实际请求会变成 .../v1/chat/completions/chat/completions 而直接 404。
    线上 SiliconFlow 的配置就是这么填的，导致所有走该配置的任务一次成功调用都没有，
    却因为失败批次静默回退原文而显示为「翻译完成」。

    Args:
        base_url: 用户配置或环境变量里的 base_url 可为 None。

    Returns:
        规整后的 base_url 输入为空时原样返回。
    """
    if not base_url:
        return base_url
    normalized = base_url.rstrip("/")
    if normalized.endswith(_CHAT_COMPLETIONS_SUFFIX):
        normalized = normalized[: -len(_CHAT_COMPLETIONS_SUFFIX)]
        logger.warning(
            "[normalize_base_url] base_url 误填了端点后缀 已自动去掉 原值 %s 修正为 %s",
            base_url, normalized,
        )
    return normalized or base_url


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


def _get_client(base_url: str | None = None, api_key: str | None = None) -> OpenAI:
    """创建 OpenAI 客户端，优先使用传入参数，fallback 到环境变量。

    max_retries 显式置 0：SDK 默认自带 2 次重试，会和 _translate_batch 的 3 次重试相乘，
    最坏情况下一个批次要打 9 次付费请求，成本不可控。重试统一由本模块负责。
    """
    resolved_base_url = base_url or os.environ.get("LLM_BASE_URL", "https://api.deepseek.com")
    return OpenAI(
        api_key=api_key or os.environ.get("LLM_API_KEY", ""),
        base_url=normalize_base_url(resolved_base_url),
        timeout=REQUEST_TIMEOUT,
        max_retries=0,
    )


def _get_model(model: str | None = None) -> str:
    """获取 LLM 模型名称，优先使用传入参数，fallback 到环境变量。"""
    return model or os.environ.get("LLM_MODEL", "deepseek-v4-flash")


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
) -> None:
    """累计单次调用的 token 用量。

    <p>之前完全没有记录用量，导致额度被烧完之后无法从日志倒推是哪些任务、花在哪里，
    只能靠词条数反推。推理模型的思维链计入 completion_tokens，这里单独累计便于识别。

    Args:
        response: LLM 响应对象。
        records: 本批次记录列表。
        depth: 当前拆分深度。
        usage: 任务级累计器 为 None 时只打 DEBUG 不累计。
    """
    raw = getattr(response, "usage", None)
    if raw is None:
        return
    prompt_tokens = getattr(raw, "prompt_tokens", None)
    completion_tokens = getattr(raw, "completion_tokens", None)
    if not isinstance(prompt_tokens, int) or not isinstance(completion_tokens, int):
        return

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
    client: OpenAI,
    model: str,
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
        client: OpenAI 客户端实例。
        model: 模型名称。
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
            client,
            model,
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
    client: OpenAI,
    model: str,
    records: list[StringRecord],
    target_lang: str,
    custom_prompt: str | None,
    dictionary_entries: list[dict] | None,
    depth: int = 0,
    budget: SplitBudget | None = None,
    usage: UsageTotals | None = None,
) -> dict[str, str]:
    """翻译单个批次的记录，包含重试逻辑和截断自动拆分。

    <p>响应不完整（finish_reason 为 length、正文为空、或译文覆盖率低于
    MIN_BATCH_COVERAGE）时把批次对半拆开重试，而不是让缺失的词条静默丢失。
    拆分会放大请求数（单批最坏 31 次），所以由 SplitBudget 在任务级封顶。

    Args:
        client: OpenAI 客户端实例。
        model: 模型名称。
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

    for attempt in range(MAX_RETRIES):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_message},
                    {"role": "user", "content": prompt},
                ],
                timeout=REQUEST_TIMEOUT,
                **_completion_kwargs(),
            )
            choice = response.choices[0]
            response_text = choice.message.content or ""
            _accumulate_usage(response, records, depth, usage)

            # 响应不完整时对半拆分重试 避免整批词条丢失
            if _should_split(choice, response_text, records, depth, budget):
                return _split_and_translate(
                    client=client,
                    model=model,
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

        except BadRequestError as e:
            # 400 属参数或内容问题 重试不会变好 直接跳出省下两次无效请求和 3 秒退避
            # 典型原因是 max_tokens 超出 provider 允许值、模型名不存在、prompt 触发内容过滤
            logger.error(
                "[_translate_batch] 请求被拒绝 不重试 records_count %d model %s error %s",
                len(records), model, str(e),
            )
            break

        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                delay = RETRY_DELAYS[attempt]
                logger.warning(
                    "[_translate_batch] LLM 调用失败 attempt %d/%d delay %ds error %s",
                    attempt + 1, MAX_RETRIES, delay, str(e),
                )
                time.sleep(delay)
            else:
                logger.error(
                    "[_translate_batch] LLM 调用重试耗尽 批次标记为失败 records_count %d error %s",
                    len(records), str(e),
                )

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
        llm_base_url: 自定义 LLM API 地址。
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

    client = _get_client(llm_base_url, llm_api_key)
    model = _get_model(llm_model)
    all_translations: dict[str, str] = {}

    # 拆分额度按批次数的 2 倍给：正常任务只有零星批次需要拆分 用不到；
    # 系统性失败时最多多打 2 倍请求就会停手 不会按 31 倍放大
    budget = SplitBudget(remaining=len(batches) * 2)
    usage = UsageTotals()

    for batch_num, batch in enumerate(batches, 1):
        batch_chars = sum(len(r.text) for r in batch)
        logger.debug(
            "[translate_records] 翻译批次 %d/%d records_count %d chars %d",
            batch_num, len(batches), len(batch), batch_chars,
        )

        batch_result = _translate_batch(
            client=client,
            model=model,
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
