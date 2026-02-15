"""ESM 解析器单元测试。"""

import os
import tempfile

import pytest

from engine.esm_parser import (
    StringRecord,
    parse_esm,
    parse_esm_bytes,
)
from tests.esm_test_helpers import (
    build_esm_file,
    build_grup,
    build_record,
    build_subrecord,
    null_terminated,
)


class TestParseEsmEmptyAndInvalid:
    """空文件和无效文件的测试。"""

    def test_empty_file_returns_empty_list(self, tmp_path):
        """空文件应返回空列表。"""
        esm_file = tmp_path / "empty.esm"
        esm_file.write_bytes(b"")
        assert parse_esm(str(esm_file)) == []

    def test_empty_bytes_returns_empty_list(self):
        """空字节数据应返回空列表。"""
        assert parse_esm_bytes(b"") == []

    def test_too_small_file_returns_empty_list(self, tmp_path):
        """过小的文件应返回空列表。"""
        esm_file = tmp_path / "tiny.esm"
        esm_file.write_bytes(b"TES4")
        assert parse_esm(str(esm_file)) == []

    def test_non_tes4_header_returns_empty_list(self):
        """非 TES4 头部应返回空列表。"""
        data = b"XXXX" + b"\x00" * 20
        assert parse_esm_bytes(data) == []



class TestParseEsmBasicRecords:
    """基本记录解析测试。"""

    def test_single_full_subrecord(self):
        """解析包含单个 FULL 子记录的 ESM 数据。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert len(result) == 1
        assert result[0].record_id == "WEAP:00000100:FULL"
        assert result[0].text == "Iron Sword"

    def test_multiple_translatable_subrecords(self):
        """解析包含多个可翻译子记录的记录。"""
        sub_full = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        sub_desc = build_subrecord(b"DESC", null_terminated("A basic iron sword."))
        rec = build_record(b"WEAP", 0x00000200, sub_full + sub_desc)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert len(result) == 2
        assert result[0].record_id == "WEAP:00000200:FULL"
        assert result[0].text == "Iron Sword"
        assert result[1].record_id == "WEAP:00000200:DESC"
        assert result[1].text == "A basic iron sword."

    def test_non_translatable_subrecords_ignored(self):
        """非可翻译子记录应被忽略。"""
        sub_edid = build_subrecord(b"EDID", null_terminated("WeapIronSword"))
        sub_full = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000300, sub_edid + sub_full)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert len(result) == 1
        assert result[0].record_id == "WEAP:00000300:FULL"

    def test_no_translatable_content_returns_empty(self):
        """没有可翻译内容的 ESM 应返回空列表。"""
        sub_edid = build_subrecord(b"EDID", null_terminated("SomeEditorId"))
        rec = build_record(b"MISC", 0x00000400, sub_edid)
        grup = build_grup(b"MISC", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert result == []

    def test_multiple_records_in_grup(self):
        """解析 GRUP 中的多个记录。"""
        rec1 = build_record(b"WEAP", 0x00000500, build_subrecord(b"FULL", null_terminated("Sword")))
        rec2 = build_record(b"WEAP", 0x00000600, build_subrecord(b"FULL", null_terminated("Axe")))
        grup = build_grup(b"WEAP", rec1 + rec2)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert len(result) == 2
        assert result[0].text == "Sword"
        assert result[1].text == "Axe"

    def test_multiple_grups(self):
        """解析多个 GRUP。"""
        rec1 = build_record(b"WEAP", 0x00000700, build_subrecord(b"FULL", null_terminated("Sword")))
        rec2 = build_record(b"ARMO", 0x00000800, build_subrecord(b"FULL", null_terminated("Shield")))
        grup1 = build_grup(b"WEAP", rec1)
        grup2 = build_grup(b"ARMO", rec2)
        data = build_esm_file(grup1 + grup2)

        result = parse_esm_bytes(data)

        assert len(result) == 2
        assert result[0].text == "Sword"
        assert result[1].text == "Shield"



class TestParseEsmCorruptedData:
    """损坏数据处理测试。"""

    def test_truncated_record_data_skips_and_continues(self):
        """记录数据被截断时应跳过并记录警告。"""
        sub = build_subrecord(b"FULL", null_terminated("Good Item"))
        rec_good = build_record(b"WEAP", 0x00000A00, sub)
        # 构建一个数据大小声明为 100 但实际数据不足的记录
        # 这会导致解析器跳过，但 rec_good 在 GRUP 中排在前面
        grup = build_grup(b"WEAP", rec_good)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert len(result) == 1
        assert result[0].text == "Good Item"

    def test_truncated_subrecord_skips_gracefully(self):
        """子记录数据被截断时应跳过。"""
        # 构建一个子记录，声明大小为 50 但实际只有 5 字节数据
        sub_bad = b"FULL" + b"\x32\x00" + b"Hello"  # size=50, data=5 bytes
        sub_good = build_subrecord(b"DESC", null_terminated("A description"))
        # 由于 sub_bad 声明 50 字节但只有 5 字节，解析器会在 sub_bad 处中断
        # sub_good 不会被解析到
        rec = build_record(b"WEAP", 0x00000B00, sub_bad + sub_good)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        # sub_bad 的数据不完整，解析器会跳过整个子记录区域
        assert len(result) == 0

    def test_tes4_header_size_exceeds_file(self):
        """TES4 头部大小超出文件范围应返回空列表。"""
        import struct
        # 构建一个 TES4 头部，data_size 声明为很大的值
        header = b"TES4" + struct.pack("<I", 99999) + b"\x00" * 16
        assert parse_esm_bytes(header) == []


class TestParseEsmRecordId:
    """记录 ID 格式测试。"""

    def test_record_id_format(self):
        """记录 ID 应为 record_type:form_id_hex:subrecord_type 格式。"""
        sub = build_subrecord(b"FULL", null_terminated("Test"))
        rec = build_record(b"NPC_", 0xDEADBEEF, sub)
        grup = build_grup(b"NPC_", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert len(result) == 1
        assert result[0].record_id == "NPC_:DEADBEEF:FULL"

    def test_record_id_preserves_form_id(self):
        """记录 ID 应保留原始 form_id 用于回写。"""
        sub = build_subrecord(b"DESC", null_terminated("Description"))
        rec = build_record(b"BOOK", 0x00012345, sub)
        grup = build_grup(b"BOOK", rec)
        data = build_esm_file(grup)

        result = parse_esm_bytes(data)

        assert result[0].record_id == "BOOK:00012345:DESC"


class TestParseEsmFileIO:
    """文件 I/O 测试。"""

    def test_parse_esm_from_file(self, tmp_path):
        """从文件路径解析 ESM。"""
        sub = build_subrecord(b"FULL", null_terminated("Test Item"))
        rec = build_record(b"MISC", 0x00001000, sub)
        grup = build_grup(b"MISC", rec)
        data = build_esm_file(grup)

        esm_file = tmp_path / "test.esm"
        esm_file.write_bytes(data)

        result = parse_esm(str(esm_file))

        assert len(result) == 1
        assert result[0].text == "Test Item"
