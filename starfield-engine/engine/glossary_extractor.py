"""术语提取器，负责从待翻译文本中调用 LLM 提取专有名词及其推荐翻译。"""

from __future__ import annotations

import json
import logging
import math
import os
import re
import time
from typing import Optional

from openai import OpenAI

from engine.esm_parser import StringRecord

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


# 重试配置
MAX_RETRIES = 3
RETRY_DELAYS = [1, 2, 4]  # 指数退避间隔（秒）


def extract_glossary(
    records: list[StringRecord],
    target_lang: str = "zh-CN",
    max_chars: int = 200000,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> list[dict]:
    """从待翻译记录中提取专有名词术语表。

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

    # 3. 创建 OpenAI 客户端（复用 llm_client.py 的模式）
    client = OpenAI(
        api_key=llm_api_key or os.environ.get("LLM_API_KEY", ""),
        base_url=llm_base_url or os.environ.get("LLM_BASE_URL", "https://api.deepseek.com"),
    )
    model = llm_model or os.environ.get("LLM_MODEL", "deepseek-v4-flash")

    # 4. 调用 LLM 提取术语（含重试逻辑）
    system_message = "You are a professional game localization terminology expert."

    for attempt in range(MAX_RETRIES):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_message},
                    {"role": "user", "content": prompt},
                ],
            )
            response_text = response.choices[0].message.content or ""

            # 5. 解析术语表
            glossary = _parse_glossary_response(response_text)

            logger.info(
                "[extract_glossary] 术语提取完成 glossary_entries_count %d",
                len(glossary),
            )

            return glossary

        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                delay = RETRY_DELAYS[attempt]
                logger.warning(
                    "[extract_glossary] LLM 调用失败 attempt %d/%d delay %ds error %s",
                    attempt + 1,
                    MAX_RETRIES,
                    delay,
                    str(e),
                )
                time.sleep(delay)
            else:
                logger.warning(
                    "[extract_glossary] LLM 调用重试耗尽 返回空术语表 records_count %d error %s",
                    len(records),
                    str(e),
                )

    return []
