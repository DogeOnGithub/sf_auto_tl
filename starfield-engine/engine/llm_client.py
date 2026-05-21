"""LLM 客户端，调用 OpenAI 兼容接口批量翻译 String_Record。"""

from __future__ import annotations

import logging
import os
import re
import time
from typing import Callable, Dict, List, Optional, Union

from openai import OpenAI

from engine.esm_parser import StringRecord
from engine.prompt_builder import build_prompt

logger = logging.getLogger(__name__)

# 重试配置
MAX_RETRIES = 3
RETRY_DELAYS = [1, 2, 4]  # 指数退避间隔（秒）

# 匹配 <...> 标签的正则
_TAG_PATTERN = re.compile(r"<[^>]+>")


def _get_client(base_url: str | None = None, api_key: str | None = None) -> OpenAI:
    """创建 OpenAI 客户端，优先使用传入参数，fallback 到环境变量。"""
    return OpenAI(
        api_key=api_key or os.environ.get("LLM_API_KEY", ""),
        base_url=base_url or os.environ.get("LLM_BASE_URL", "https://api.deepseek.com"),
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


def _parse_response(response_text: str, records: list[StringRecord]) -> dict[str, str]:
    """解析 LLM 返回的翻译文本，按编号与原始记录 ID 匹配。

    LLM 返回格式为 [编号] 译文，按编号匹配对应的原始记录。

    Args:
        response_text: LLM 返回的翻译文本。
        records: 原始 StringRecord 列表。

    Returns:
        record_id -> translated_text 的映射字典。
    """
    import re

    # 解析 [编号] 译文 格式，支持多行译文
    translations: dict[int, str] = {}
    current_idx: int | None = None
    current_lines: list[str] = []

    for line in response_text.strip().split("\n"):
        match = re.match(r"\[(\d+)\]\s*(.*)", line)
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

    result: dict[str, str] = {}

    for i, record in enumerate(records):
        idx = i + 1
        translated = translations.get(idx, "")
        if translated:
            result[record.record_id] = translated
        else:
            logger.warning(
                "[_parse_response] 编号 %d 无对应译文 record_id %s",
                idx, record.record_id,
            )
            result[record.record_id] = record.text

        logger.info(
            "[_parse_response] record_id %s 原文 %s 译文 %s",
            record.record_id, record.text, result[record.record_id],
        )

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
            result = _parse_response(response_text, records)
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
    max_batch_chars: int = 200000,
    max_batch_records: int = 800,
    on_batch_done: Callable[[int], None] | None = None,
    on_batch_translated: Callable[[dict[str, str], list[StringRecord]], None] | None = None,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> dict[str, str]:
    """批量翻译 StringRecord 列表。

    根据文本总字符数动态分批 充分利用 LLM 上下文窗口。
    每批独立重试 失败的批次记录错误日志 不影响其他批次。

    Args:
        records: 待翻译的 StringRecord 列表。
        target_lang: 目标语言 默认 zh-CN。
        custom_prompt: 用户自定义 Prompt None 时使用默认模板。
        dictionary_entries: 词典词条列表。
        batch_size: 已废弃 保留参数兼容性 实际使用 max_batch_chars 和 max_batch_records。
        max_batch_chars: 每批文本总字符数上限 默认 200000。
        max_batch_records: 每批最大记录数上限 防止编号过多导致 LLM 混乱 默认 800。
        on_batch_done: 每完成一个 Batch 后的回调函数 参数为当前已翻译总数。
        on_batch_translated: 每完成一个 Batch 后的回调函数 参数为该批翻译结果和对应的原始记录。

    Returns:
        record_id -> translated_text 的映射字典。
        成功翻译的记录包含翻译文本 失败批次的记录不包含在结果中。
    """
    if not records:
        logger.info("[translate_records] 无待翻译记录")
        return {}

    # 按文本字符数动态分批
    batches = _split_batches(records, max_batch_chars, max_batch_records)

    logger.info(
        "[translate_records] 开始翻译 records_count %d batches_count %d target_lang %s max_batch_chars %d max_batch_records %d",
        len(records), len(batches), target_lang, max_batch_chars, max_batch_records,
    )

    client = _get_client(llm_base_url, llm_api_key)
    model = _get_model(llm_model)
    all_translations: dict[str, str] = {}

    for batch_num, batch in enumerate(batches, 1):
        batch_chars = sum(len(r.text) for r in batch)
        logger.info(
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
        )
        all_translations.update(batch_result)

        if on_batch_translated is not None and batch_result:
            on_batch_translated(batch_result, batch)

        if on_batch_done is not None:
            on_batch_done(len(all_translations))

    logger.info(
        "[translate_records] 翻译完成 total %d translated %d",
        len(records), len(all_translations),
    )
    return all_translations

