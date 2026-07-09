"""Strings 文件重组器，将翻译后的文本回写为 .STRINGS/.DLSTRINGS/.ILSTRINGS 文件。

回写时保留原始文件的全部字符串 ID 与目录顺序，仅替换文本；未翻译到的条目
（如空串或翻译失败）按原文保留。因每条独立写入，原文件中共享 offset 的去重结构
不再保留（功能等价，游戏按 ID→offset→文本 读取不受影响）。

输出编码固定为 UTF-8（Starfield）。输出文件名与输入保持一致。
"""

from __future__ import annotations

import logging
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Dict

from engine.strings_parser import (
    FILE_KINDS,
    find_strings_file,
    parse_strings_file,
)

logger = logging.getLogger(__name__)


@dataclass
class StringsWriteResult:
    """Strings 目录写入结果。"""

    output_dir: str
    file_count: int


def _encode_string(text: str, length_prefixed: bool) -> bytes:
    """将单条文本编码为数据区字节（UTF-8 + null 终止符）。

    Args:
        text: 待编码文本。
        length_prefixed: 是否带 uint32 长度前缀（.DLSTRINGS/.ILSTRINGS）。

    Returns:
        编码后的字节。
    """
    raw = text.encode("utf-8") + b"\x00"
    if length_prefixed:
        return struct.pack("<I", len(raw)) + raw
    return raw


def _rewrite_strings_file(
    data: bytes,
    translations_by_id: Dict[int, str],
    length_prefixed: bool,
) -> bytes:
    """重写单个 Strings 文件，替换译文并重建目录与数据区。

    Args:
        data: 原始 Strings 文件字节。
        translations_by_id: string_id -> 译文 的映射。
        length_prefixed: 数据区是否带 uint32 长度前缀。

    Returns:
        重写后的 Strings 文件字节。
    """
    entries = parse_strings_file(data, length_prefixed)

    directory = bytearray()
    data_section = bytearray()
    for string_id, original_text in entries:
        text = translations_by_id.get(string_id, original_text)
        offset = len(data_section)
        directory += struct.pack("<II", string_id, offset)
        data_section += _encode_string(text, length_prefixed)

    header = struct.pack("<II", len(entries), len(data_section))
    return bytes(header) + bytes(directory) + bytes(data_section)


def _group_translations_by_type(translations: Dict[str, str]) -> Dict[str, Dict[int, str]]:
    """将 record_id 维度的译文按 record_type 分组为 string_id -> 译文。

    record_id 格式为 record_type:string_id:sub_tag。

    Args:
        translations: record_id -> 译文 的映射。

    Returns:
        record_type -> {string_id: 译文} 的映射。
    """
    by_type: Dict[str, Dict[int, str]] = {}
    for record_id, text in translations.items():
        parts = record_id.split(":")
        if len(parts) < 2:
            continue
        record_type = parts[0]
        try:
            string_id = int(parts[1])
        except ValueError:
            logger.warning("[_group_translations_by_type] record_id 解析异常 record_id %s", record_id)
            continue
        by_type.setdefault(record_type, {})[string_id] = text
    return by_type


def write_strings_dir(
    original_dir: str,
    translations: Dict[str, str],
    output_dir: str,
) -> StringsWriteResult:
    """将翻译后的文本回写为三个 Strings 文件，输出到指定目录。

    Args:
        original_dir: 原始 Strings 文件所在目录。
        translations: record_id -> 译文 的映射。
        output_dir: 输出目录（不存在则创建）。

    Returns:
        StringsWriteResult，包含输出目录与写出的文件数。
    """
    logger.info(
        "[write_strings_dir] 开始写入 Strings 目录 original_dir %s output_dir %s translations_count %d",
        original_dir, output_dir, len(translations),
    )

    src = Path(original_dir)
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    by_type = _group_translations_by_type(translations)

    file_count = 0
    for ext, (record_type, _sub_tag, length_prefixed) in FILE_KINDS.items():
        file_path = find_strings_file(src, ext)
        if file_path is None:
            logger.info("[write_strings_dir] 未找到文件 ext %s original_dir %s", ext, original_dir)
            continue

        data = file_path.read_bytes()
        translations_by_id = by_type.get(record_type, {})
        new_data = _rewrite_strings_file(data, translations_by_id, length_prefixed)

        (out / file_path.name).write_bytes(new_data)
        file_count += 1
        logger.info(
            "[write_strings_dir] 写入文件完成 file %s replaced %d",
            file_path.name, len(translations_by_id),
        )

    logger.info("[write_strings_dir] 写入完成 output_dir %s file_count %d", output_dir, file_count)
    return StringsWriteResult(output_dir=str(out), file_count=file_count)
