"""ESM 文件解析器，负责解析 Starfield ESM 二进制文件并提取可翻译文本记录。"""

from __future__ import annotations

import logging
import struct
import zlib
from dataclasses import dataclass
from typing import List, Optional, Tuple

logger = logging.getLogger(__name__)

# 包含可翻译文本的子记录类型（任意记录类型下都翻译）
TRANSLATABLE_SUBRECORD_TYPES = frozenset({b"FULL", b"DESC", b"NNAM", b"SHRT", b"RNAM"})

# 排除规则：特定记录类型下的子记录虽然在通用列表中，但实际包含二进制数据
# 格式: (record_type, subrecord_type)
# NPC_:RNAM = 种族 FormID 引用（4 字节二进制），NPC_:NNAM = 二进制数据
# SMQN:NNAM = Story Manager Quest Node 二进制数据
NON_TRANSLATABLE_OVERRIDES = frozenset({
    (b"NPC_", b"RNAM"),   # NPC 种族 FormID 引用（4 字节二进制）
    (b"NPC_", b"NNAM"),   # NPC 二进制数据（非任务目标文本）
    (b"SMQN", b"NNAM"),   # Story Manager Quest Node 二进制数据
    (b"FURN", b"NNAM"),   # 家具二进制数据（非文本）
})

# 需要按"记录类型 + 子记录类型"组合判断的可翻译条目
# 格式: (record_type, subrecord_type)
TRANSLATABLE_COMBINATIONS = frozenset({
    (b"INFO", b"NAM1"),   # NPC 对话文本
    (b"QUST", b"CNAM"),   # 任务日志描述
    (b"QUST", b"NAM2"),   # 任务阶段文本
    (b"TMLM", b"ITXT"),   # 终端菜单选项
    (b"TMLM", b"BTXT"),   # 终端正文内容
    (b"TMLM", b"UNAM"),   # 终端操作结果
    (b"NPC_", b"LNAM"),   # NPC 所属组织名
    (b"REFR", b"UNAM"),   # 地图标记名
    (b"NPC_", b"ATTX"),   # 交互提示文本
    (b"FURN", b"ATTX"),   # 家具交互提示文本（如 Drive、Sit）
    (b"MESG", b"ITXT"),   # 消息框按钮文本
    (b"PERK", b"EPF2"),   # Perk 效果描述文本
    (b"BOOK", b"CNAM"),   # 书籍正文内容
    (b"MGEF", b"DNAM"),   # 魔法效果描述
    (b"LVLN", b"ONAM"),   # 等级列表 NPC 覆盖名称
    (b"GPOF", b"DNAM"),   # 游戏设置选项描述
    (b"GPOF", b"VOVS"),   # 游戏设置选项值描述
    (b"AVIF", b"NLDT"),   # Actor Value 长描述文本
    (b"QUST", b"QMDP"),   # 任务标记显示参数
    (b"QUST", b"QMDT"),   # 任务标记显示标题
    (b"QUST", b"QMSU"),   # 任务摘要文本
})

# 记录头部大小：type(4) + data_size(4) + flags(4) + form_id(4) + revision(4) + version(2) + unknown(2)
RECORD_HEADER_SIZE = 24

# GRUP 头部大小：type(4) + group_size(4) + label(4) + group_type(4) + stamp(4) + unknown(4)
GRUP_HEADER_SIZE = 24

# 记录压缩标志位（flags 字段的 bit 18）
COMPRESSED_FLAG = 0x00040000

# 子记录头部大小：type(4) + data_size(2)
SUBRECORD_HEADER_SIZE = 6


@dataclass
class StringRecord:
    """ESM 文件中的一条可翻译文本记录。"""

    record_id: str
    text: str
    editor_id: str = ""



def _decode_text(data: bytes) -> str:
    """解码子记录中的文本数据，去除末尾的 null 终止符。

    ESM 文件中的文本大多为 UTF-8 编码，但部分 mod 使用 Windows-1252 编码
    （例如右单引号 0x92、破折号 0x97 等）。先尝试 UTF-8，若出现 replacement
    character 则回退到 Windows-1252。
    """
    if data.endswith(b"\x00"):
        data = data[:-1]
    text = data.decode("utf-8", errors="replace")
    if "\ufffd" in text:
        text = data.decode("windows-1252", errors="replace")
    return text


def _build_record_id(record_type: bytes, form_id: int, subrecord_type: bytes) -> str:
    """构建唯一的记录 ID，格式为 record_type:form_id_hex:subrecord_type。"""
    return f"{record_type.decode('ascii')}:{form_id:08X}:{subrecord_type.decode('ascii')}"


def _is_printable_text(text: str) -> bool:
    """检查文本是否为可打印的有效文本，过滤二进制数据被误解码的情况。"""
    if not text:
        return False
    # 包含 replacement character（\ufffd）说明 UTF-8 解码失败，属于二进制数据
    if "\ufffd" in text:
        return False
    printable_count = sum(1 for c in text if c.isprintable() or c in ('\n', '\r', '\t'))
    return printable_count / len(text) >= 0.9


def _parse_subrecords(data: bytes, record_type: bytes, form_id: int) -> tuple[list[StringRecord], str]:
    """解析记录内的子记录，提取可翻译文本和 Editor ID。

    判断逻辑：
    1. 子记录类型在 TRANSLATABLE_SUBRECORD_TYPES 中（任意记录类型下都翻译）
    2. (记录类型, 子记录类型) 组合在 TRANSLATABLE_COMBINATIONS 中

    同一 form_id 下多个同类型子记录通过序号区分：
    第一个为 RECORD_TYPE:FORM_ID:SUBRECORD_TYPE，
    后续为 RECORD_TYPE:FORM_ID:SUBRECORD_TYPE#1、#2 等。

    支持 XXXX 超大子记录：当子记录数据超过 65535 字节时，引擎会先写入一个 XXXX
    子记录（包含 uint32 的真实大小），紧接着的子记录 size 字段为 0，实际大小以
    XXXX 中的值为准。

    返回 (records, editor_id)。
    """
    records = []
    offset = 0
    editor_id = ""
    # 统计同一 form_id 下每种子记录类型出现的次数，用于生成唯一 record_id
    sub_type_counts: dict[bytes, int] = {}
    # XXXX 子记录提供的超大数据大小，供下一个子记录使用
    xxxx_size: int | None = None

    while offset < len(data):
        if offset + SUBRECORD_HEADER_SIZE > len(data):
            logger.warning(
                "[_parse_subrecords] 子记录头部不完整 offset %d record_type %s form_id %08X",
                offset, record_type.decode("ascii", errors="replace"), form_id,
            )
            break

        sub_type = data[offset : offset + 4]
        sub_size = struct.unpack_from("<H", data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE

        # 处理 XXXX 超大子记录标记
        if sub_type == b"XXXX":
            if sub_size == 4 and offset + 4 <= len(data):
                xxxx_size = struct.unpack_from("<I", data, offset)[0]
            else:
                logger.warning(
                    "[_parse_subrecords] XXXX 子记录格式异常 sub_size %d offset %d form_id %08X",
                    sub_size, offset, form_id,
                )
            offset += sub_size
            continue

        # 如果前一个子记录是 XXXX，使用其提供的 32 位大小替代当前的 16 位 size
        if xxxx_size is not None:
            sub_size = xxxx_size
            xxxx_size = None

        if offset + sub_size > len(data):
            logger.warning(
                "[_parse_subrecords] 子记录数据不完整 sub_type %s sub_size %d offset %d",
                sub_type.decode("ascii", errors="replace"), sub_size, offset,
            )
            break

        # 提取 Editor ID（EDID 子记录）
        if sub_type == b"EDID" and sub_size > 0 and not editor_id:
            editor_id = _decode_text(data[offset : offset + sub_size])

        is_translatable = (
            (sub_type in TRANSLATABLE_SUBRECORD_TYPES
             and (record_type, sub_type) not in NON_TRANSLATABLE_OVERRIDES)
            or (record_type, sub_type) in TRANSLATABLE_COMBINATIONS
        )

        if is_translatable and sub_size > 0:
            text = _decode_text(data[offset : offset + sub_size])
            if text and _is_printable_text(text):
                count = sub_type_counts.get(sub_type, 0)
                if count == 0:
                    record_id = _build_record_id(record_type, form_id, sub_type)
                else:
                    record_id = _build_record_id(record_type, form_id, sub_type) + f"#{count}"
                sub_type_counts[sub_type] = count + 1
                records.append(StringRecord(record_id=record_id, text=text))

        offset += sub_size

    return records, editor_id



def _parse_records(data: bytes, offset: int, end: int) -> tuple[list[StringRecord], int]:
    """递归解析记录和 GRUP，提取所有可翻译文本记录。

    返回 (string_records, new_offset)。
    """
    records: list[StringRecord] = []

    while offset < end:
        # 检查是否有足够的字节读取类型标识
        if offset + 4 > end:
            logger.warning("[_parse_records] 数据不足以读取记录类型 offset %d end %d", offset, end)
            break

        rec_type = data[offset : offset + 4]

        if rec_type == b"GRUP":
            # 解析 GRUP
            if offset + GRUP_HEADER_SIZE > end:
                logger.warning("[_parse_records] GRUP 头部不完整 offset %d", offset)
                break

            group_size = struct.unpack_from("<I", data, offset + 4)[0]

            if group_size < GRUP_HEADER_SIZE:
                logger.warning("[_parse_records] GRUP 大小异常 group_size %d offset %d", group_size, offset)
                break

            group_end = offset + group_size
            if group_end > end:
                logger.warning(
                    "[_parse_records] GRUP 超出数据范围 group_end %d end %d offset %d",
                    group_end, end, offset,
                )
                # 尝试用剩余数据继续解析
                group_end = end

            # 递归解析 GRUP 内部的记录
            inner_records, _ = _parse_records(data, offset + GRUP_HEADER_SIZE, group_end)
            records.extend(inner_records)
            offset = group_end

        else:
            # 解析普通记录
            if offset + RECORD_HEADER_SIZE > end:
                logger.warning("[_parse_records] 记录头部不完整 offset %d rec_type %s", offset, rec_type.decode("ascii", errors="replace"))
                break

            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]

            record_data_start = offset + RECORD_HEADER_SIZE
            record_data_end = record_data_start + data_size

            if record_data_end > end:
                logger.warning(
                    "[_parse_records] 记录数据超出范围 rec_type %s data_size %d offset %d",
                    rec_type.decode("ascii", errors="replace"), data_size, offset,
                )
                break

            record_data = data[record_data_start:record_data_end]

            # 处理压缩记录
            if flags & COMPRESSED_FLAG:
                if len(record_data) < 4:
                    logger.warning(
                        "[_parse_records] 压缩记录数据不足 rec_type %s form_id %08X offset %d",
                        rec_type.decode("ascii", errors="replace"), form_id, offset,
                    )
                    offset = record_data_end
                    continue
                decompressed_size = struct.unpack_from("<I", record_data, 0)[0]
                try:
                    record_data = zlib.decompress(record_data[4:], bufsize=decompressed_size)
                except zlib.error:
                    logger.warning(
                        "[_parse_records] zlib 解压失败 rec_type %s form_id %08X offset %d",
                        rec_type.decode("ascii", errors="replace"), form_id, offset,
                    )
                    offset = record_data_end
                    continue

            # 解析子记录提取可翻译文本
            try:
                sub_records, editor_id = _parse_subrecords(record_data, rec_type, form_id)
                if editor_id:
                    for sr in sub_records:
                        sr.editor_id = editor_id
                records.extend(sub_records)
            except Exception:
                logger.warning(
                    "[_parse_records] 解析子记录异常 rec_type %s form_id %08X offset %d",
                    rec_type.decode("ascii", errors="replace"), form_id, offset,
                    exc_info=True,
                )

            offset = record_data_end

    return records, offset


def parse_esm(file_path: str) -> list[StringRecord]:
    """解析 ESM 文件，提取所有可翻译的 StringRecord。

    Args:
        file_path: ESM 文件路径。

    Returns:
        包含所有可翻译文本记录的列表。空文件返回空列表。
    """
    logger.info("[parse_esm] 开始解析 ESM 文件 file_path %s", file_path)

    with open(file_path, "rb") as f:
        data = f.read()

    if len(data) == 0:
        logger.info("[parse_esm] 空文件 file_path %s", file_path)
        return []

    # ESM 文件最小需要一个 TES4 记录头部
    if len(data) < RECORD_HEADER_SIZE:
        logger.warning("[parse_esm] 文件过小无法解析 file_path %s size %d", file_path, len(data))
        return []

    # 验证文件头部是否为 TES4 记录
    header_type = data[0:4]
    if header_type != b"TES4":
        logger.warning("[parse_esm] 文件头部不是 TES4 file_path %s header %s", file_path, header_type.decode("ascii", errors="replace"))
        return []

    # 跳过 TES4 头部记录
    header_data_size = struct.unpack_from("<I", data, 4)[0]
    first_record_offset = RECORD_HEADER_SIZE + header_data_size

    if first_record_offset > len(data):
        logger.warning("[parse_esm] TES4 头部大小超出文件范围 file_path %s", file_path)
        return []

    # 解析 TES4 之后的所有记录
    records, _ = _parse_records(data, first_record_offset, len(data))

    logger.info("[parse_esm] 解析完成 file_path %s records_count %d", file_path, len(records))
    return records


def parse_esm_bytes(data: bytes) -> list[StringRecord]:
    """从字节数据解析 ESM 内容，提取所有可翻译的 StringRecord。

    主要用于测试和内存中处理场景。

    Args:
        data: ESM 文件的二进制数据。

    Returns:
        包含所有可翻译文本记录的列表。空数据返回空列表。
    """
    if len(data) == 0:
        return []

    if len(data) < RECORD_HEADER_SIZE:
        logger.warning("[parse_esm_bytes] 数据过小无法解析 size %d", len(data))
        return []

    header_type = data[0:4]
    if header_type != b"TES4":
        logger.warning("[parse_esm_bytes] 数据头部不是 TES4 header %s", header_type.decode("ascii", errors="replace"))
        return []

    header_data_size = struct.unpack_from("<I", data, 4)[0]
    first_record_offset = RECORD_HEADER_SIZE + header_data_size

    if first_record_offset > len(data):
        logger.warning("[parse_esm_bytes] TES4 头部大小超出数据范围")
        return []

    records, _ = _parse_records(data, first_record_offset, len(data))
    return records
