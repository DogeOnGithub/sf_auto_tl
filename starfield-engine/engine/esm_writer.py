"""ESM 文件重组器，负责将翻译后的文本回写到 ESM 二进制文件。"""

from __future__ import annotations

import logging
import shutil
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

from engine.esm_parser import (
    COMPRESSED_FLAG,
    GRUP_HEADER_SIZE,
    NON_TRANSLATABLE_OVERRIDES,
    RECORD_HEADER_SIZE,
    SUBRECORD_HEADER_SIZE,
    TRANSLATABLE_COMBINATIONS,
    TRANSLATABLE_SUBRECORD_TYPES,
    _decode_text,
    _is_printable_text,
)

logger = logging.getLogger(__name__)


@dataclass
class WriteResult:
    """ESM 写入结果。"""

    backup_path: str
    output_path: str


def _build_record_id(record_type: bytes, form_id: int, subrecord_type: bytes) -> str:
    """构建唯一的记录 ID，格式为 record_type:form_id_hex:subrecord_type。"""
    return f"{record_type.decode('ascii')}:{form_id:08X}:{subrecord_type.decode('ascii')}"


def _rewrite_subrecords(
    data: bytes,
    record_type: bytes,
    form_id: int,
    translations: Dict[str, str],
) -> bytes:
    """重写记录内的子记录，替换已翻译的文本。

    同一 form_id 下多个同类型子记录通过序号区分：
    第一个为 RECORD_TYPE:FORM_ID:SUBRECORD_TYPE，
    后续为 RECORD_TYPE:FORM_ID:SUBRECORD_TYPE#1、#2 等。

    支持 XXXX 超大子记录：当子记录数据超过 65535 字节时，前面会有一个 XXXX
    子记录提供 uint32 的真实大小，紧接着的子记录 size 字段为 0。

    返回重写后的子记录字节数据。
    """
    parts: list[bytes] = []
    offset = 0
    sub_type_counts: dict[bytes, int] = {}
    # XXXX 子记录提供的超大数据大小，供下一个子记录使用
    xxxx_size: int | None = None

    while offset < len(data):
        if offset + SUBRECORD_HEADER_SIZE > len(data):
            parts.append(data[offset:])
            break

        sub_type = data[offset : offset + 4]
        sub_size = struct.unpack_from("<H", data, offset + 4)[0]

        # 处理 XXXX 超大子记录标记
        if sub_type == b"XXXX":
            xxxx_header_end = offset + SUBRECORD_HEADER_SIZE + sub_size
            if sub_size == 4 and offset + SUBRECORD_HEADER_SIZE + 4 <= len(data):
                xxxx_size = struct.unpack_from("<I", data, offset + SUBRECORD_HEADER_SIZE)[0]
            # 保留原始 XXXX 子记录（后续可能需要更新）
            # 先暂存，等处理下一个子记录时决定是否需要修改
            parts.append(data[offset : xxxx_header_end])
            offset = xxxx_header_end
            continue

        # 如果前一个子记录是 XXXX，使用其提供的 32 位大小
        actual_xxxx = xxxx_size
        if xxxx_size is not None:
            sub_size = xxxx_size
            xxxx_size = None

        if offset + SUBRECORD_HEADER_SIZE + sub_size > len(data):
            parts.append(data[offset:])
            break

        sub_data = data[offset + SUBRECORD_HEADER_SIZE : offset + SUBRECORD_HEADER_SIZE + sub_size]

        is_translatable = (
            (sub_type in TRANSLATABLE_SUBRECORD_TYPES
             and (record_type, sub_type) not in NON_TRANSLATABLE_OVERRIDES)
            or (record_type, sub_type) in TRANSLATABLE_COMBINATIONS
        )
        if is_translatable and sub_size > 0:
            # 与 parser 保持一致：仅对可打印文本计数和生成 record_id
            text = _decode_text(sub_data)
            if text and _is_printable_text(text):
                count = sub_type_counts.get(sub_type, 0)
                if count == 0:
                    record_id = _build_record_id(record_type, form_id, sub_type)
                else:
                    record_id = _build_record_id(record_type, form_id, sub_type) + f"#{count}"
                sub_type_counts[sub_type] = count + 1

                if record_id in translations:
                    new_text = translations[record_id]
                    new_data = new_text.encode("utf-8") + b"\x00"
                    new_size = len(new_data)

                    if actual_xxxx is not None:
                        # 原来有 XXXX 前缀，需要更新 XXXX 中的大小值
                        # 替换刚才暂存的 XXXX 子记录
                        parts[-1] = b"XXXX" + struct.pack("<H", 4) + struct.pack("<I", new_size)
                        parts.append(sub_type + struct.pack("<H", 0) + new_data)
                    elif new_size > 0xFFFF:
                        # 翻译后大小超过 uint16，需要新增 XXXX 前缀
                        parts.append(b"XXXX" + struct.pack("<H", 4) + struct.pack("<I", new_size))
                        parts.append(sub_type + struct.pack("<H", 0) + new_data)
                    else:
                        # 普通大小，直接写入
                        if actual_xxxx is not None:
                            # 原来有 XXXX 但现在不需要了，移除暂存的 XXXX
                            parts[-1] = b""
                        parts.append(sub_type + struct.pack("<H", new_size) + new_data)
                    offset += SUBRECORD_HEADER_SIZE + sub_size
                    continue

        # 保持原始子记录不变
        parts.append(data[offset : offset + SUBRECORD_HEADER_SIZE + sub_size])
        offset += SUBRECORD_HEADER_SIZE + sub_size

    return b"".join(parts)


def _rewrite_records(
    data: bytes,
    offset: int,
    end: int,
    translations: Dict[str, str],
) -> bytes:
    """递归重写记录和 GRUP，替换翻译文本并调整长度字段。

    返回重写后的字节数据。
    """
    parts: list[bytes] = []

    while offset < end:
        if offset + 4 > end:
            parts.append(data[offset:end])
            break

        rec_type = data[offset : offset + 4]

        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                parts.append(data[offset:end])
                break

            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            group_end = min(offset + group_size, end)

            # 保留 GRUP 头部（稍后更新 group_size）
            grup_header = bytearray(data[offset : offset + GRUP_HEADER_SIZE])

            # 递归重写 GRUP 内部记录
            inner_data = _rewrite_records(data, offset + GRUP_HEADER_SIZE, group_end, translations)

            # 更新 group_size
            new_group_size = GRUP_HEADER_SIZE + len(inner_data)
            struct.pack_into("<I", grup_header, 4, new_group_size)

            parts.append(bytes(grup_header) + inner_data)
            offset = group_end

        else:
            if offset + RECORD_HEADER_SIZE > end:
                parts.append(data[offset:end])
                break

            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]

            record_data_start = offset + RECORD_HEADER_SIZE
            record_data_end = min(record_data_start + data_size, end)

            # 保留记录头部（稍后更新 data_size 和 flags）
            rec_header = bytearray(data[offset : offset + RECORD_HEADER_SIZE])

            original_sub_data = data[record_data_start:record_data_end]

            if flags & COMPRESSED_FLAG:
                # 压缩记录：先解压 再重写子记录 再压缩回去
                if len(original_sub_data) < 4:
                    parts.append(data[offset:record_data_end])
                    offset = record_data_end
                    continue
                decompressed_size = struct.unpack_from("<I", original_sub_data, 0)[0]
                try:
                    decompressed_data = zlib.decompress(original_sub_data[4:], bufsize=decompressed_size)
                except zlib.error:
                    logger.warning(
                        "[_rewrite_records] zlib 解压失败 跳过记录 rec_type %s form_id %08X",
                        rec_type.decode("ascii", errors="replace"), form_id,
                    )
                    parts.append(data[offset:record_data_end])
                    offset = record_data_end
                    continue

                new_sub_data = _rewrite_subrecords(decompressed_data, rec_type, form_id, translations)

                # 重新压缩
                compressed = zlib.compress(new_sub_data)
                new_record_data = struct.pack("<I", len(new_sub_data)) + compressed

                # 更新 data_size（压缩后的大小 + 4 字节解压大小头）
                struct.pack_into("<I", rec_header, 4, len(new_record_data))

                parts.append(bytes(rec_header) + new_record_data)
            else:
                # 非压缩记录：直接重写子记录
                new_sub_data = _rewrite_subrecords(original_sub_data, rec_type, form_id, translations)

                # 更新 data_size
                struct.pack_into("<I", rec_header, 4, len(new_sub_data))

                parts.append(bytes(rec_header) + new_sub_data)

            offset = record_data_end

    return b"".join(parts)


def write_esm(
    original_path: str,
    translations: Dict[str, str],
    output_path: str,
    backup_path: Optional[str] = None,
) -> WriteResult:
    """将翻译后的文本回写到 ESM 文件。

    先备份原始文件，再生成包含翻译文本的新 ESM 文件。

    Args:
        original_path: 原始 ESM 文件路径。
        translations: 记录 ID 到翻译文本的映射，格式为 {record_id: translated_text}。
        output_path: 翻译后 ESM 文件的输出路径。
        backup_path: 原始文件备份路径，默认为 {original_name}.backup.esm。

    Returns:
        WriteResult，包含备份路径和输出路径。
    """
    logger.info("[write_esm] 开始写入 ESM 文件 original_path %s output_path %s", original_path, output_path)

    original = Path(original_path)

    # 确定备份路径
    if backup_path is None:
        backup_path = str(original.with_suffix(".backup.esm"))

    # 备份原始文件
    shutil.copy2(str(original), backup_path)
    logger.info("[write_esm] 已备份原始文件 backup_path %s", backup_path)

    # 读取原始数据
    with open(original_path, "rb") as f:
        data = f.read()

    # 重写数据
    new_data = rewrite_esm_bytes(data, translations)

    # 写入输出文件
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(new_data)

    logger.info("[write_esm] 写入完成 output_path %s translations_count %d", output_path, len(translations))
    return WriteResult(backup_path=backup_path, output_path=output_path)


def rewrite_esm_bytes(data: bytes, translations: Dict[str, str]) -> bytes:
    """在内存中重写 ESM 数据，替换翻译文本并调整长度字段。

    主要用于测试和内存中处理场景。

    Args:
        data: 原始 ESM 文件的二进制数据。
        translations: 记录 ID 到翻译文本的映射。

    Returns:
        重写后的 ESM 二进制数据。
    """
    if len(data) < RECORD_HEADER_SIZE:
        return data

    header_type = data[0:4]
    if header_type != b"TES4":
        return data

    header_data_size = struct.unpack_from("<I", data, 4)[0]
    first_record_offset = RECORD_HEADER_SIZE + header_data_size

    if first_record_offset > len(data):
        return data

    # TES4 头部保持不变
    tes4_part = data[:first_record_offset]

    # 重写后续记录
    records_part = _rewrite_records(data, first_record_offset, len(data), translations)

    return tes4_part + records_part
