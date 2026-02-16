"""Prompt 构建器，负责组装发送给 LLM 的完整翻译 Prompt。"""

from __future__ import annotations

import logging
from typing import Optional

logger = logging.getLogger(__name__)

DEFAULT_PROMPT = (
    "你是一个专业的游戏本地化翻译专家。请将以下游戏 Mod 文本翻译为简体中文。\n"
    "\n"
    "严格规则（必须遵守）：\n"
    "1. 输入格式为编号行 [1] 原文1 [2] 原文2 ...，输出必须严格按相同编号格式 [1] 译文1 [2] 译文2 ...\n"
    "2. 输出行数必须与输入行数完全一致，不得合并、拆分或遗漏任何一行\n"
    "3. 每行只输出 [编号] 译文，不要添加任何解释、注释或额外内容\n"
    "4. <> 包裹的标签是占位符，必须原样保留不翻译，例如 <alias> <br> <Global=SQ_Companions01>\n"
    "\n"
    "翻译要求：\n"
    "1. 保持游戏术语的一致性和准确性\n"
    "2. 翻译应自然流畅，符合中文游戏玩家的阅读习惯\n"
    "3. 保留原文中的格式标记、变量占位符和特殊符号不做翻译\n"
    "4. 对于专有名词，如无明确译法则保留原文"
)

DICTIONARY_SECTION_HEADER = "以下词条必须保持指定翻译：\n"

TEXT_SECTION_HEADER = "待翻译文本：\n"


def build_prompt(
    texts_to_translate: list[str],
    custom_prompt: Optional[str] = None,
    dictionary_entries: Optional[list[dict]] = None,
) -> str:
    """组装发送给 LLM 的完整 Prompt。

    组装逻辑：
    1. 基础指令 = custom_prompt（非空时）或 DEFAULT_PROMPT
    2. 如果 dictionary_entries 非空，追加词典约束段
    3. 追加待翻译文本
    4. 返回完整 Prompt

    Args:
        texts_to_translate: 待翻译的文本列表。
        custom_prompt: 用户自定义 Prompt，为 None 或空字符串时使用默认模板。
        dictionary_entries: 词典词条列表，每个词条为 {"sourceText": str, "targetText": str}。

    Returns:
        组装后的完整 Prompt 字符串。
    """
    logger.info(
        "[build_prompt] 开始构建 Prompt custom_prompt_set %s dict_entries_count %d texts_count %d",
        custom_prompt is not None and len(custom_prompt) > 0,
        len(dictionary_entries) if dictionary_entries else 0,
        len(texts_to_translate),
    )

    # 1. 基础指令
    base_instruction = custom_prompt if custom_prompt else DEFAULT_PROMPT

    parts = [base_instruction]

    # 2. 词典约束段
    if dictionary_entries:
        dict_lines = [DICTIONARY_SECTION_HEADER]
        for entry in dictionary_entries:
            source = entry.get("sourceText", "")
            target = entry.get("targetText", "")
            if source and target:
                dict_lines.append(f"{source} → {target}")
        if len(dict_lines) > 1:
            parts.append("\n".join(dict_lines))

    # 3. 待翻译文本（编号格式）
    numbered_lines = [f"[{i + 1}] {text}" for i, text in enumerate(texts_to_translate)]
    text_section = TEXT_SECTION_HEADER + "\n".join(numbered_lines)
    parts.append(text_section)

    prompt = "\n\n".join(parts)

    logger.info("[build_prompt] Prompt 构建完成 total_length %d", len(prompt))
    return prompt
