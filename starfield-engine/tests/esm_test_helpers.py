"""ESM 二进制数据构建辅助函数，用于测试。"""

from __future__ import annotations

import struct
from typing import Optional


def build_subrecord(sub_type: bytes, data: bytes) -> bytes:
    """构建一个子记录：type(4) + size(2) + data。"""
    return sub_type + struct.pack("<H", len(data)) + data


def build_record(rec_type: bytes, form_id: int, subrecords: bytes, flags: int = 0) -> bytes:
    """构建一个记录：type(4) + data_size(4) + flags(4) + form_id(4) + revision(4) + version(2) + unknown(2) + data。"""
    header = rec_type + struct.pack("<I", len(subrecords)) + struct.pack("<I", flags)
    header += struct.pack("<I", form_id) + struct.pack("<I", 0)  # revision
    header += struct.pack("<H", 0) + struct.pack("<H", 0)  # version + unknown
    return header + subrecords


def build_grup(label: bytes, records_data: bytes, group_type: int = 0) -> bytes:
    """构建一个 GRUP：type(4) + group_size(4) + label(4) + group_type(4) + stamp(4) + unknown(4) + data。"""
    group_size = 24 + len(records_data)  # GRUP header is 24 bytes
    header = b"GRUP" + struct.pack("<I", group_size) + label
    header += struct.pack("<I", group_type) + struct.pack("<I", 0) + struct.pack("<I", 0)
    return header + records_data


def build_tes4_header(subrecords: Optional[bytes] = None) -> bytes:
    """构建 TES4 文件头部记录。"""
    if subrecords is None:
        # 最小的 TES4 头部，包含一个 HEDR 子记录
        hedr_data = struct.pack("<f", 1.0) + struct.pack("<I", 0) + struct.pack("<I", 0)
        subrecords = build_subrecord(b"HEDR", hedr_data)
    return build_record(b"TES4", 0, subrecords)


def build_esm_file(records_after_header: bytes, tes4_subrecords: Optional[bytes] = None) -> bytes:
    """构建完整的 ESM 文件数据：TES4 头部 + 后续记录/GRUP。"""
    return build_tes4_header(tes4_subrecords) + records_after_header


def null_terminated(text: str) -> bytes:
    """将文本转为 null 终止的 UTF-8 字节。"""
    return text.encode("utf-8") + b"\x00"
