"""术语提取器，负责从待翻译文本中调用 LLM 提取专有名词及其推荐翻译。"""

from __future__ import annotations

import json
import logging
import math
import re
import time
from typing import Optional

from engine.esm_parser import StringRecord
# 复用 llm_client 的可选参数组装与用量解析、凭证来源解析 保持整个引擎对 LLM 的约束口径一致
from engine.llm_client import _accumulate_usage, _completion_kwargs, _resolve_source
from engine.llm_config import (
    DEFAULT_GLOSSARY_MAX_CHARS,
    MAX_GLOSSARY_TERMS,
    MAX_RETRIES,
    MIN_GLOSSARY_MAX_CHARS,
    POOL_MAX_MEMBER_SWITCHES,
    REQUEST_TIMEOUT,
    RETRY_DELAYS,
)
from engine.llm_pool import ERROR_KIND_BAD_REQUEST, ERROR_KIND_TRANSIENT, PoolMember, classify_error

logger = logging.getLogger(__name__)


def _build_extraction_prompt(texts: list[str], target_lang: str) -> str:
    """构建术语提取 Prompt，要求 LLM 返回 JSON 格式的专有名词列表。

    按设计文档中的术语提取 Prompt 模板，将目标语言和待分析文本填入模板，
    生成完整的术语提取 Prompt。

    Args:
        texts: 待分析的文本列表。
        target_lang: 目标语言。

    Returns:
        完整的术语提取 Prompt。
    """
    logger.info(
        "[_build_extraction_prompt] 构建术语提取 Prompt target_lang %s texts_count %d",
        target_lang,
        len(texts),
    )

    joined_texts = "\n".join(texts)

    prompt = (
        f"你是一个专业的游戏本地化术语专家。请从以下游戏 Mod 文本中提取所有专有名词"
        f"（包括人名、地名、组织名、物品名、技能名、种族名等），"
        f"并为每个专有名词提供一个统一的{target_lang}翻译。\n"
        f"\n"
        f"要求：\n"
        f"1. 仅提取专有名词，不要提取普通词汇\n"
        f"2. 每个专有名词只出现一次，不要重复\n"
        f"3. 以 JSON 数组格式返回，每个元素包含 sourceText（原文）和 targetText（翻译）\n"
        f"4. 不要添加任何额外解释或注释\n"
        f"5. 最多返回 {MAX_GLOSSARY_TERMS} 条，超出时优先保留出现频率高、容易被误译的名词\n"
        f"\n"
        f"返回格式示例：\n"
        f'[\n'
        f'  {{"sourceText": "Vasco", "targetText": "瓦斯科"}},\n'
        f'  {{"sourceText": "Constellation", "targetText": "群星组织"}}\n'
        f']\n'
        f"\n"
        f"以下是待分析的文本：\n"
        f"{joined_texts}"
    )

    logger.info(
        "[_build_extraction_prompt] Prompt 构建完成 total_length %d",
        len(prompt),
    )

    return prompt


def _parse_glossary_response(response_text: str) -> list[dict]:
    """解析 LLM 返回的术语表 JSON。

    支持 LLM 返回的 JSON 被 markdown 代码块包裹的情况，
    对相同 sourceText 去重（保留最后一条），跳过缺少必要字段的条目。

    Args:
        response_text: LLM 返回的文本（应包含 JSON 数组）。

    Returns:
        去重后的术语表列表。解析失败返回空列表。
    """
    try:
        text = response_text.strip()

        # Strip markdown code block wrappers if present (```json\n...\n``` or ```\n...\n```)
        text = re.sub(r"^```(?:json)?\s*\n?", "", text)
        text = re.sub(r"\n?```\s*$", "", text)

        entries = json.loads(text)

        if not isinstance(entries, list):
            logger.warning(
                "[_parse_glossary_response] 解析结果不是数组 type %s",
                type(entries).__name__,
            )
            return []

        # Deduplicate by sourceText, keeping the last occurrence; skip invalid entries
        seen: dict[str, dict] = {}
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            source = entry.get("sourceText")
            target = entry.get("targetText")
            if not isinstance(source, str) or not source:
                continue
            if not isinstance(target, str) or not target:
                continue
            seen[source] = {"sourceText": source, "targetText": target}

        result = list(seen.values())

        logger.info(
            "[_parse_glossary_response] 解析完成 total_parsed %d deduplicated %d",
            len(entries),
            len(result),
        )

        return result

    except Exception as e:
        logger.warning(
            "[_parse_glossary_response] 解析术语表失败 error %s",
            str(e),
        )
        return []


def _sample_texts(records: list[StringRecord], max_chars: int) -> list[str]:
    """当文本总量超过 max_chars 时，均匀间隔采样选取文本子集。

    采用均匀间隔采样算法：根据平均文本长度估算可选取的记录数量，
    然后按均匀间隔从列表中选取记录，确保覆盖不同位置的文本。
    相邻采样索引之间的间隔差异不超过 1。

    当单条文本超过 max_chars 时，该条独立保留（不跳过）。

    Args:
        records: 完整的 StringRecord 列表。
        max_chars: 字符数上限。

    Returns:
        采样后的文本列表。
    """
    if not records:
        return []

    all_texts = [r.text for r in records]
    total_chars = sum(len(t) for t in all_texts)

    # When total text chars ≤ max_chars, return ALL texts
    if total_chars <= max_chars:
        logger.info(
            "[_sample_texts] 文本总量未超限 total_chars %d max_chars %d records %d",
            total_chars,
            max_chars,
            len(records),
        )
        return all_texts

    # Estimate how many records to select based on average text length
    n = len(records)
    avg_len = total_chars / n
    num_to_select = max(1, int(max_chars / avg_len)) if avg_len > 0 else n
    # Clamp to total number of records
    num_to_select = min(num_to_select, n)

    # Calculate uniform indices using math.floor(i * n / num_to_select)
    # This produces indices with adjacent gaps differing by at most 1
    step = n / num_to_select
    indices = [math.floor(i * step) for i in range(num_to_select)]

    # Accumulate chars as we select, stop if we'd exceed max_chars
    # (but always include at least the first record)
    sampled: list[str] = []
    accumulated = 0
    for idx in indices:
        text = all_texts[idx]
        text_len = len(text)
        if sampled and accumulated + text_len > max_chars:
            break
        sampled.append(text)
        accumulated += text_len

    logger.info(
        "[_sample_texts] 均匀间隔采样完成 total_records %d sampled %d total_chars %d accumulated_chars %d max_chars %d",
        n,
        len(sampled),
        total_chars,
        accumulated,
        max_chars,
    )

    return sampled


def extract_glossary(
    records: list[StringRecord],
    target_lang: str = "zh-CN",
    max_chars: int = DEFAULT_GLOSSARY_MAX_CHARS,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> list[dict]:
    """从待翻译记录中提取专有名词术语表。

    <p>响应被输出上限截断时会缩小采样重试：截断的 JSON 解析必然失败，
    直接返回空术语表相当于这次调用白花钱，缩小一半采样再试一次更划算。

    Args:
        records: 待翻译的 StringRecord 列表。
        target_lang: 目标语言。
        max_chars: 术语提取文本字符数上限，超过时采用均匀采样。
        llm_base_url: 自定义 LLM API 地址。
        llm_api_key: 自定义 LLM API Key。
        llm_model: 自定义 LLM 模型名称。

    Returns:
        术语表，格式为 [{"sourceText": str, "targetText": str}, ...]。
        失败时返回空列表。
    """
    if not records:
        logger.info("[extract_glossary] 无待翻译记录 跳过术语提取")
        return []

    logger.info(
        "[extract_glossary] 开始术语提取 records_count %d target_lang %s max_chars %d",
        len(records),
        target_lang,
        max_chars,
    )

    # 1. 采样文本
    sampled_texts = _sample_texts(records, max_chars)
    if not sampled_texts:
        logger.info("[extract_glossary] 采样文本为空 跳过术语提取")
        return []

    logger.info(
        "[extract_glossary] 采样完成 sampled_texts_count %d",
        len(sampled_texts),
    )

    # 2. 构建 Prompt
    prompt = _build_extraction_prompt(sampled_texts, target_lang)

    # 3. 解析凭证来源（自带 KEY 走 FixedSource 否则走默认凭证池）
    source = _resolve_source(llm_base_url, llm_api_key, llm_model)
    if source is None:
        logger.warning("[extract_glossary] 无可用 LLM 凭证 跳过术语提取")
        return []
    member = source.acquire()
    if member is None:
        logger.warning("[extract_glossary] 无可用池成员 跳过术语提取")
        return []
    client = source.client_for(member)

    # 4. 调用 LLM 提取术语（含错误分类、成员切换与截断缩采样）
    system_message = "You are a professional game localization terminology expert."
    current_max_chars = max_chars

    tried: set = set()
    switches = 0
    transient_attempts = 0
    total_attempts = 0
    max_total_attempts = MAX_RETRIES + POOL_MAX_MEMBER_SWITCHES

    def next_member(current: PoolMember) -> Optional[PoolMember]:
        """换一个还没试过的成员，不支持切换或已无成员可换时返回 None。

        <p>supports_failover 的判断收在这里：自带凭证的来源会无条件返回同一个成员，
        漏判就会「切换」到自己身上，把重试次数悄悄放大一倍。
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
                response, records, 0, None,
            )
            source.record_success(member, prompt_tokens, completion_tokens, reasoning_tokens)

            # 4.5 输出被截断时缩小采样重试 截断的 JSON 解析必然失败
            if getattr(choice, "finish_reason", None) == "length":
                if current_max_chars // 2 >= MIN_GLOSSARY_MAX_CHARS:
                    current_max_chars //= 2
                    logger.warning(
                        "[extract_glossary] 术语表被输出上限截断 缩小采样重试 new_max_chars %d attempt %d/%d",
                        current_max_chars,
                        total_attempts,
                        max_total_attempts,
                    )
                    sampled_texts = _sample_texts(records, current_max_chars)
                    prompt = _build_extraction_prompt(sampled_texts, target_lang)
                    continue
                logger.warning(
                    "[extract_glossary] 术语表仍被截断且采样已到下限 放弃提取 max_chars %d",
                    current_max_chars,
                )

            # 5. 解析术语表
            glossary = _parse_glossary_response(response_text)

            logger.info(
                "[extract_glossary] 术语提取完成 glossary_entries_count %d",
                len(glossary),
            )

            return glossary

        except Exception as e:
            kind = classify_error(e)
            source.record_failure(member, kind, str(e))

            # 自带凭证没有成员可换 非 400 一律按瞬时错误退避重试 与改造前一致
            if not source.supports_failover() and kind != ERROR_KIND_BAD_REQUEST:
                kind = ERROR_KIND_TRANSIENT

            if kind == ERROR_KIND_BAD_REQUEST:
                # 400 属参数或内容问题 重试不会变好 直接放弃 术语提取本身是可降级的
                # 这里不像翻译那样再换个成员试：术语表缺失只是少了译名统一约束 不值得为它多付一次请求
                logger.warning(
                    "[extract_glossary] 请求被拒绝 不重试 返回空术语表 model %s error %s",
                    member.model,
                    str(e),
                )
                return []

            if kind == ERROR_KIND_TRANSIENT:
                transient_attempts += 1
                if transient_attempts < MAX_RETRIES and total_attempts < max_total_attempts:
                    delay = RETRY_DELAYS[min(transient_attempts - 1, len(RETRY_DELAYS) - 1)]
                    logger.warning(
                        "[extract_glossary] LLM 调用失败 attempt %d/%d delay %ds error %s",
                        transient_attempts,
                        MAX_RETRIES,
                        delay,
                        str(e),
                    )
                    time.sleep(delay)
                    continue

            # 瞬时错误重试耗尽 或成员级错误（限流、鉴权、余额、模型名）：换成员
            candidate = next_member(member) if switches < POOL_MAX_MEMBER_SWITCHES else None
            if candidate is not None:
                member = candidate
                client = source.client_for(member)
                switches += 1
                transient_attempts = 0
                logger.warning(
                    "[extract_glossary] 切换池成员重试术语提取 kind %s member %s",
                    kind,
                    member.name,
                )
                continue

            logger.warning(
                "[extract_glossary] LLM 调用失败且无成员可换 返回空术语表 kind %s records_count %d error %s",
                kind,
                len(records),
                str(e),
            )
            return []

    logger.warning("[extract_glossary] 尝试次数用尽 返回空术语表 records_count %d", len(records))
    return []
