"""Strings 文件解析器，解析开启本地化的 Starfield mod 的外部 Strings 文件。

开启本地化（Localized）的 ESM/ESP 会把可翻译文本从记录子记录搬到三个外部
Strings 文件：.STRINGS / .DLSTRINGS / .ILSTRINGS，ESM 内仅保留 4 字节的字符串 ID
引用。因此翻译本地化 mod 只需解析这三个文件的 (字符串ID → 文本) 平表，翻译后按
原 ID 回写即可，无需触碰 ESM。

文件格式（小端）：
- Header：uint32 count + uint32 data_size（数据区字节数）
- 目录：  count × (uint32 string_id, uint32 offset)，offset 相对数据区起点
- 数据区：
    - .STRINGS              每条为 null 结尾的原始字符串，无长度前缀
    - .DLSTRINGS/.ILSTRINGS 每条为 uint32 长度（含 null）+ 字符串字节 + null

编码固定为 UTF-8（Starfield）。

record_id 采用与 ESM 一致的三段式 record_type:id:sub_tag，使去重、缓存、确认记录
等既有逻辑无需改动即可复用：
- record_type：STRINGS / DLSTRINGS / ILSTRINGS（缓存命名空间，与 ESM 天然隔离）
- 中间段：字符串 ID（十进制）
- sub_tag：固定标签（STR/DL/IL），保证相同文本在同一文件类型内去重与缓存命中
"""

from __future__ import annotations

import logging
import struct
from pathlib import Path
from typing import List

from engine.esm_parser import StringRecord, _decode_text

logger = logging.getLogger(__name__)

# Header：uint32 count + uint32 data_size
HEADER_SIZE = 8
# 目录条目：uint32 string_id + uint32 offset
DIRECTORY_ENTRY_SIZE = 8

# Strings 文件扩展名（小写）
STRINGS_EXT = ".strings"
DLSTRINGS_EXT = ".dlstrings"
ILSTRINGS_EXT = ".ilstrings"

# 三种文件类型配置：ext -> (record_type, sub_tag, length_prefixed)
# length_prefixed 为 True 表示数据区每条带 uint32 长度前缀（.DLSTRINGS/.ILSTRINGS）
FILE_KINDS: dict[str, tuple[str, str, bool]] = {
    STRINGS_EXT: ("STRINGS", "STR", False),
    DLSTRINGS_EXT: ("DLSTRINGS", "DL", True),
    ILSTRINGS_EXT: ("ILSTRINGS", "IL", True),
}

# 必须齐全的三个 Strings 文件扩展名
REQUIRED_EXTENSIONS = tuple(FILE_KINDS.keys())


def build_strings_record_id(record_type: str, string_id: int, sub_tag: str) -> str:
    """构建 Strings 记录 ID，格式为 record_type:string_id:sub_tag。"""
    return f"{record_type}:{string_id}:{sub_tag}"


def find_strings_file(dir_path: Path, ext: str) -> Path | None:
    """在目录中查找指定扩展名的 Strings 文件（大小写不敏感）。

    Args:
        dir_path: Strings 文件所在目录。
        ext: 目标扩展名（小写，如 ".strings"）。

    Returns:
        匹配到的文件路径，未找到返回 None。
    """
    if not dir_path.is_dir():
        logger.warning("[find_strings_file] 目录不存在 dir_path %s", dir_path)
        return None
    for p in sorted(dir_path.iterdir()):
        if p.is_file() and p.name.lower().endswith(ext):
            return p
    return None


def _read_string_at(data: bytes, abs_offset: int, length_prefixed: bool) -> str:
    """读取数据区中某个偏移处的字符串。

    Args:
        data: 整个文件的字节数据。
        abs_offset: 字符串在文件中的绝对偏移（数据区起点 + 目录 offset）。
        length_prefixed: 是否带 uint32 长度前缀。

    Returns:
        解码后的字符串（已去除末尾 null）。
    """
    if length_prefixed:
        if abs_offset + 4 > len(data):
            logger.warning("[_read_string_at] 长度前缀越界 abs_offset %d size %d", abs_offset, len(data))
            return ""
        length = struct.unpack_from("<I", data, abs_offset)[0]
        start = abs_offset + 4
        raw = data[start : start + length]
    else:
        end = data.find(b"\x00", abs_offset)
        if end < 0:
            end = len(data)
        raw = data[abs_offset:end]
    return _decode_text(raw)


def parse_strings_file(data: bytes, length_prefixed: bool) -> list[tuple[int, str]]:
    """解析单个 Strings 文件字节，返回有序的 (string_id, text) 列表。

    保留目录顺序与全部条目（含空串），供回写时完整保留所有字符串 ID。

    Args:
        data: 单个 Strings 文件的二进制数据。
        length_prefixed: 数据区是否带 uint32 长度前缀。

    Returns:
        有序的 (string_id, text) 列表。空文件（count=0）返回空列表。
    """
    if len(data) < HEADER_SIZE:
        logger.warning("[parse_strings_file] 文件过小无法解析 size %d", len(data))
        return []

    count, data_size = struct.unpack_from("<II", data, 0)
    if count == 0:
        return []

    directory_start = HEADER_SIZE
    data_section_start = HEADER_SIZE + count * DIRECTORY_ENTRY_SIZE

    entries: list[tuple[int, str]] = []
    for i in range(count):
        entry_offset = directory_start + i * DIRECTORY_ENTRY_SIZE
        if entry_offset + DIRECTORY_ENTRY_SIZE > len(data):
            logger.warning("[parse_strings_file] 目录条目越界 index %d size %d", i, len(data))
            break
        string_id, rel_offset = struct.unpack_from("<II", data, entry_offset)
        text = _read_string_at(data, data_section_start + rel_offset, length_prefixed)
        entries.append((string_id, text))

    return entries


def parse_strings_dir(dir_path: str) -> List[StringRecord]:
    """解析 Strings 目录下的三个文件，提取所有可翻译的 StringRecord。

    仅提取非空文本用于翻译；空串跳过（回写时由 writer 按原文保留）。

    Args:
        dir_path: 包含 .STRINGS/.DLSTRINGS/.ILSTRINGS 三个文件的目录路径。

    Returns:
        包含所有可翻译文本记录的列表。
    """
    logger.info("[parse_strings_dir] 开始解析 Strings 目录 dir_path %s", dir_path)

    base = Path(dir_path)
    records: List[StringRecord] = []

    for ext, (record_type, sub_tag, length_prefixed) in FILE_KINDS.items():
        file_path = find_strings_file(base, ext)
        if file_path is None:
            logger.info("[parse_strings_dir] 未找到文件 ext %s dir_path %s", ext, dir_path)
            continue

        data = file_path.read_bytes()
        entries = parse_strings_file(data, length_prefixed)
        extracted = 0
        for string_id, text in entries:
            if not text or not text.strip():
                continue
            record_id = build_strings_record_id(record_type, string_id, sub_tag)
            records.append(StringRecord(record_id=record_id, text=text))
            extracted += 1

        logger.info(
            "[parse_strings_dir] 解析文件完成 file %s total %d translatable %d",
            file_path.name, len(entries), extracted,
        )

    logger.info("[parse_strings_dir] 解析完成 dir_path %s records_count %d", dir_path, len(records))
    return records
