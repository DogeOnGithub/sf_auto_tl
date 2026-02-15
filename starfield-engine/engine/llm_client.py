"""LLM 客户端，调用 OpenAI 兼容接口批量翻译 String_Record。"""

from __future__ import annotations

import logging
import os
import time
from typing import Dict, List, Optional, Union

from openai import OpenAI

from engine.esm_parser import StringRecord
from engine.prompt_builder import build_prompt

logger = logging.getLogger(__name__)

# 重试配置
MAX_RETRIES = 3
RETRY_DELAYS = [1, 2, 4]  # 指数退避间隔（秒）


def _get_client() -> OpenAI:
    """创建 OpenAI 客户端，从环境变量读取配置。"""
    return OpenAI(
        api_key=os.environ.get("LLM_API_KEY", "sk-76916acd42eb444dbf456625df9cba49"),
        base_url=os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1"),
    )


def _get_model() -> str:
    """获取 LLM 模型名称，默认 deepseek-reasoner。"""
    return os.environ.get("LLM_MODEL", "deepseek-reasoner")


def _parse_response(response_text: str, records: list[StringRecord]) -> dict[str, str]:
    """解析 LLM 返回的翻译文本，按行与原始记录 ID 匹配。

    LLM 返回的每行翻译对应输入的每行原文，按顺序匹配。

    Args:
        response_text: LLM 返回的翻译文本。
        records: 原始 StringRecord 列表。

    Returns:
        record_id -> translated_text 的映射字典。
    """
    lines = response_text.strip().split("\n")
    result: dict[str, str] = {}

    for i, record in enumerate(records):
        if i < len(lines):
            translated = lines[i].strip()
            if translated:
                result[record.record_id] = translated
            else:
                # 空行回退到原文
                result[record.record_id] = record.text
        else:
            # 返回行数不足，回退到原文
            logger.warning(
                "[_parse_response] 翻译行数不足 expected %d got %d record_id %s",
                len(records), len(lines), record.record_id,
            )
            result[record.record_id] = record.text

    return result


def _translate_batch(
    client: OpenAI,
    model: str,
    records: list[StringRecord],
    target_lang: str,
    custom_prompt: str | None,
    dictionary_entries: list[dict] | None,
) -> dict[str, str]:
    """翻译单个批次的记录，包含重试逻辑。

    Args:
        client: OpenAI 客户端实例。
        model: 模型名称。
        records: 待翻译的 StringRecord 列表。
        target_lang: 目标语言。
        custom_prompt: 用户自定义 Prompt。
        dictionary_entries: 词典词条列表。

    Returns:
        record_id -> translated_text 的映射字典。

    Raises:
        无异常抛出，失败时返回空字典并记录错误日志。
    """
    texts = [r.text for r in records]
    prompt = build_prompt(
        texts_to_translate=texts,
        custom_prompt=custom_prompt,
        dictionary_entries=dictionary_entries,
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
            )
            response_text = response.choices[0].message.content or ""
            return _parse_response(response_text, records)

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


def translate_records(
    records: list[StringRecord],
    target_lang: str = "zh-CN",
    custom_prompt: str | None = None,
    dictionary_entries: list[dict] | None = None,
    batch_size: int = 20,
) -> dict[str, str]:
    """批量翻译 StringRecord 列表。

    将记录按 batch_size 分批，逐批调用 LLM 翻译。每批独立重试，
    失败的批次记录错误日志，不影响其他批次。

    Args:
        records: 待翻译的 StringRecord 列表。
        target_lang: 目标语言，默认 zh-CN。
        custom_prompt: 用户自定义 Prompt，None 时使用默认模板。
        dictionary_entries: 词典词条列表。
        batch_size: 每批翻译的记录数，默认 20。

    Returns:
        record_id -> translated_text 的映射字典。
        成功翻译的记录包含翻译文本，失败批次的记录不包含在结果中。
    """
    if not records:
        logger.info("[translate_records] 无待翻译记录")
        return {}

    logger.info(
        "[translate_records] 开始翻译 records_count %d batch_size %d target_lang %s",
        len(records), batch_size, target_lang,
    )

    client = _get_client()
    model = _get_model()
    all_translations: dict[str, str] = {}

    # 按 batch_size 分批
    for i in range(0, len(records), batch_size):
        batch = records[i : i + batch_size]
        batch_num = i // batch_size + 1
        total_batches = (len(records) + batch_size - 1) // batch_size

        logger.info(
            "[translate_records] 翻译批次 %d/%d records_count %d",
            batch_num, total_batches, len(batch),
        )

        batch_result = _translate_batch(
            client=client,
            model=model,
            records=batch,
            target_lang=target_lang,
            custom_prompt=custom_prompt,
            dictionary_entries=dictionary_entries,
        )
        all_translations.update(batch_result)

    logger.info(
        "[translate_records] 翻译完成 total %d translated %d",
        len(records), len(all_translations),
    )
    return all_translations
