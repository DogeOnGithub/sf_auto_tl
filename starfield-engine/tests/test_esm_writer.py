"""ESM 重组器单元测试。"""

import struct

import pytest

from engine.esm_parser import parse_esm, parse_esm_bytes
from engine.esm_writer import WriteResult, rewrite_esm_bytes, write_esm
from tests.esm_test_helpers import (
    build_esm_file,
    build_grup,
    build_record,
    build_subrecord,
    null_terminated,
)


class TestRewriteEsmBytesBasic:
    """基本文本替换测试。"""

    def test_single_text_replacement(self):
        """替换单个 FULL 子记录的文本。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {"WEAP:00000100:FULL": "铁剑"}
        result = rewrite_esm_bytes(data, translations)

        records = parse_esm_bytes(result)
        assert len(records) == 1
        assert records[0].text == "铁剑"
        assert records[0].record_id == "WEAP:00000100:FULL"

    def test_no_translations_returns_identical(self):
        """空翻译字典应返回与原始相同的数据。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = rewrite_esm_bytes(data, {})
        assert result == data

    def test_unmatched_translation_key_ignored(self):
        """不匹配的翻译 key 应被忽略，数据不变。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {"WEAP:99999999:FULL": "不存在的翻译"}
        result = rewrite_esm_bytes(data, translations)
        assert result == data



class TestRewriteEsmBytesLengthChanges:
    """文本长度变化测试。"""

    def test_shorter_text_adjusts_sizes(self):
        """替换为更短的文本时应正确调整长度字段。"""
        sub = build_subrecord(b"FULL", null_terminated("A very long item name"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {"WEAP:00000100:FULL": "短"}
        result = rewrite_esm_bytes(data, translations)

        # 验证可以正确解析
        records = parse_esm_bytes(result)
        assert len(records) == 1
        assert records[0].text == "短"

        # 验证文件变小了
        assert len(result) < len(data)

    def test_longer_text_adjusts_sizes(self):
        """替换为更长的文本时应正确调整长度字段。"""
        sub = build_subrecord(b"FULL", null_terminated("Hi"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {"WEAP:00000100:FULL": "这是一个非常长的翻译文本用于测试"}
        result = rewrite_esm_bytes(data, translations)

        records = parse_esm_bytes(result)
        assert len(records) == 1
        assert records[0].text == "这是一个非常长的翻译文本用于测试"

        # 验证文件变大了
        assert len(result) > len(data)

    def test_subrecord_size_field_correct(self):
        """子记录的 size 字段应正确反映新文本长度。"""
        sub = build_subrecord(b"FULL", null_terminated("Test"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {"WEAP:00000100:FULL": "测试文本"}
        result = rewrite_esm_bytes(data, translations)

        # 找到 FULL 子记录并验证 size 字段
        expected_text_bytes = "测试文本".encode("utf-8") + b"\x00"
        expected_size = len(expected_text_bytes)

        # 在结果中搜索 FULL 子记录
        idx = result.find(b"FULL")
        # 跳过 GRUP label 中可能出现的 FULL，找记录内的
        while idx != -1:
            sub_size = struct.unpack_from("<H", result, idx + 4)[0]
            sub_data = result[idx + 6 : idx + 6 + sub_size]
            if sub_data == expected_text_bytes:
                assert sub_size == expected_size
                return
            idx = result.find(b"FULL", idx + 1)

        pytest.fail("未找到翻译后的 FULL 子记录")


class TestRewriteEsmBytesMultipleReplacements:
    """多个替换测试。"""

    def test_multiple_subrecords_in_one_record(self):
        """同一记录中多个可翻译子记录都应被替换。"""
        sub_full = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        sub_desc = build_subrecord(b"DESC", null_terminated("A basic iron sword."))
        rec = build_record(b"WEAP", 0x00000200, sub_full + sub_desc)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {
            "WEAP:00000200:FULL": "铁剑",
            "WEAP:00000200:DESC": "一把基础的铁剑。",
        }
        result = rewrite_esm_bytes(data, translations)

        records = parse_esm_bytes(result)
        assert len(records) == 2
        assert records[0].text == "铁剑"
        assert records[1].text == "一把基础的铁剑。"

    def test_multiple_records_in_grup(self):
        """GRUP 中多个记录都应被正确替换。"""
        rec1 = build_record(b"WEAP", 0x00000500, build_subrecord(b"FULL", null_terminated("Sword")))
        rec2 = build_record(b"WEAP", 0x00000600, build_subrecord(b"FULL", null_terminated("Axe")))
        grup = build_grup(b"WEAP", rec1 + rec2)
        data = build_esm_file(grup)

        translations = {
            "WEAP:00000500:FULL": "剑",
            "WEAP:00000600:FULL": "斧",
        }
        result = rewrite_esm_bytes(data, translations)

        records = parse_esm_bytes(result)
        assert len(records) == 2
        assert records[0].text == "剑"
        assert records[1].text == "斧"

    def test_multiple_grups(self):
        """多个 GRUP 中的记录都应被正确替换。"""
        rec1 = build_record(b"WEAP", 0x00000700, build_subrecord(b"FULL", null_terminated("Sword")))
        rec2 = build_record(b"ARMO", 0x00000800, build_subrecord(b"FULL", null_terminated("Shield")))
        grup1 = build_grup(b"WEAP", rec1)
        grup2 = build_grup(b"ARMO", rec2)
        data = build_esm_file(grup1 + grup2)

        translations = {
            "WEAP:00000700:FULL": "剑",
            "ARMO:00000800:FULL": "盾",
        }
        result = rewrite_esm_bytes(data, translations)

        records = parse_esm_bytes(result)
        assert len(records) == 2
        assert records[0].text == "剑"
        assert records[1].text == "盾"



class TestNonTranslatablePreservation:
    """非文本数据保持完整性测试。"""

    def test_non_translatable_subrecords_preserved(self):
        """非可翻译子记录应保持不变。"""
        sub_edid = build_subrecord(b"EDID", null_terminated("WeapIronSword"))
        sub_full = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        sub_data = build_subrecord(b"DATA", struct.pack("<f", 10.5))
        rec = build_record(b"WEAP", 0x00000300, sub_edid + sub_full + sub_data)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        translations = {"WEAP:00000300:FULL": "铁剑"}
        result = rewrite_esm_bytes(data, translations)

        # 验证 EDID 和 DATA 子记录仍然存在且不变
        # 通过解析验证翻译正确
        records = parse_esm_bytes(result)
        assert len(records) == 1
        assert records[0].text == "铁剑"

        # 验证 EDID 数据完整
        idx = result.find(b"EDID")
        assert idx != -1
        edid_size = struct.unpack_from("<H", result, idx + 4)[0]
        edid_data = result[idx + 6 : idx + 6 + edid_size]
        assert edid_data == null_terminated("WeapIronSword")

    def test_tes4_header_preserved(self):
        """TES4 头部应保持不变。"""
        sub = build_subrecord(b"FULL", null_terminated("Test"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        # 获取原始 TES4 头部
        header_data_size = struct.unpack_from("<I", data, 4)[0]
        original_tes4 = data[: 24 + header_data_size]

        translations = {"WEAP:00000100:FULL": "测试"}
        result = rewrite_esm_bytes(data, translations)

        # TES4 头部应完全相同
        result_tes4 = result[: 24 + header_data_size]
        assert result_tes4 == original_tes4


class TestRoundTripConsistency:
    """往返一致性测试：parse -> write（无翻译）-> 比较。"""

    def test_round_trip_single_record(self):
        """单条记录的往返一致性。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = rewrite_esm_bytes(data, {})
        assert result == data

    def test_round_trip_multiple_records(self):
        """多条记录的往返一致性。"""
        sub1 = build_subrecord(b"FULL", null_terminated("Sword"))
        sub2 = build_subrecord(b"DESC", null_terminated("A sword."))
        rec1 = build_record(b"WEAP", 0x00000100, sub1 + sub2)
        rec2 = build_record(b"ARMO", 0x00000200, build_subrecord(b"FULL", null_terminated("Shield")))
        grup1 = build_grup(b"WEAP", rec1)
        grup2 = build_grup(b"ARMO", rec2)
        data = build_esm_file(grup1 + grup2)

        result = rewrite_esm_bytes(data, {})
        assert result == data

    def test_round_trip_with_non_translatable(self):
        """包含非可翻译子记录的往返一致性。"""
        sub_edid = build_subrecord(b"EDID", null_terminated("WeapIronSword"))
        sub_full = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        sub_data = build_subrecord(b"DATA", struct.pack("<fI", 10.5, 100))
        rec = build_record(b"WEAP", 0x00000100, sub_edid + sub_full + sub_data)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        result = rewrite_esm_bytes(data, {})
        assert result == data

    def test_round_trip_empty_esm(self):
        """空 ESM（仅 TES4 头部）的往返一致性。"""
        data = build_esm_file(b"")
        result = rewrite_esm_bytes(data, {})
        assert result == data



class TestWriteEsmFileIO:
    """文件 I/O 和备份测试。"""

    def test_backup_file_created(self, tmp_path):
        """写入前应创建原始文件的备份。"""
        sub = build_subrecord(b"FULL", null_terminated("Test"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        original_file = tmp_path / "test.esm"
        original_file.write_bytes(data)
        output_file = tmp_path / "test_translated.esm"

        result = write_esm(str(original_file), {"WEAP:00000100:FULL": "测试"}, str(output_file))

        # 备份文件应存在且内容与原始文件相同
        from pathlib import Path
        backup = Path(result.backup_path)
        assert backup.exists()
        assert backup.read_bytes() == data

    def test_backup_default_path(self, tmp_path):
        """默认备份路径应为 {original_name}.backup.esm。"""
        sub = build_subrecord(b"FULL", null_terminated("Test"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        original_file = tmp_path / "mymod.esm"
        original_file.write_bytes(data)
        output_file = tmp_path / "mymod_translated.esm"

        result = write_esm(str(original_file), {}, str(output_file))

        assert result.backup_path == str(tmp_path / "mymod.backup.esm")

    def test_custom_backup_path(self, tmp_path):
        """支持自定义备份路径。"""
        sub = build_subrecord(b"FULL", null_terminated("Test"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        original_file = tmp_path / "test.esm"
        original_file.write_bytes(data)
        output_file = tmp_path / "output.esm"
        backup_file = tmp_path / "backups" / "test_backup.esm"
        backup_file.parent.mkdir(parents=True, exist_ok=True)

        result = write_esm(str(original_file), {}, str(output_file), backup_path=str(backup_file))

        assert result.backup_path == str(backup_file)
        assert backup_file.read_bytes() == data

    def test_output_file_contains_translations(self, tmp_path):
        """输出文件应包含翻译后的文本。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        original_file = tmp_path / "test.esm"
        original_file.write_bytes(data)
        output_file = tmp_path / "test_translated.esm"

        write_esm(str(original_file), {"WEAP:00000100:FULL": "铁剑"}, str(output_file))

        records = parse_esm(str(output_file))
        assert len(records) == 1
        assert records[0].text == "铁剑"

    def test_original_file_unchanged(self, tmp_path):
        """原始文件在写入后应保持不变。"""
        sub = build_subrecord(b"FULL", null_terminated("Iron Sword"))
        rec = build_record(b"WEAP", 0x00000100, sub)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        original_file = tmp_path / "test.esm"
        original_file.write_bytes(data)
        output_file = tmp_path / "test_translated.esm"

        write_esm(str(original_file), {"WEAP:00000100:FULL": "铁剑"}, str(output_file))

        # 原始文件不应被修改
        assert original_file.read_bytes() == data

    def test_write_result_paths(self, tmp_path):
        """WriteResult 应包含正确的路径。"""
        data = build_esm_file(b"")
        original_file = tmp_path / "test.esm"
        original_file.write_bytes(data)
        output_file = tmp_path / "output.esm"

        result = write_esm(str(original_file), {}, str(output_file))

        assert result.output_path == str(output_file)
        assert result.backup_path == str(tmp_path / "test.backup.esm")


class TestEdgeCases:
    """边界情况测试。"""

    def test_empty_data(self):
        """空数据应原样返回。"""
        assert rewrite_esm_bytes(b"", {}) == b""

    def test_too_small_data(self):
        """过小的数据应原样返回。"""
        data = b"TES4" + b"\x00" * 10
        assert rewrite_esm_bytes(data, {}) == data

    def test_non_tes4_header(self):
        """非 TES4 头部应原样返回。"""
        data = b"XXXX" + b"\x00" * 20
        assert rewrite_esm_bytes(data, {}) == data


import zlib

from engine.esm_parser import COMPRESSED_FLAG


class TestCompressedRecords:
    """压缩记录写回测试（修复 NPC_ FULL、NPC_ SHRT、CELL FULL、PNDT FULL 写回失败）。"""

    def _build_compressed_record(self, rec_type, form_id, subrecords):
        """构建压缩记录：子记录数据先压缩，再加上解压大小头。"""
        decompressed_size = len(subrecords)
        compressed = zlib.compress(subrecords)
        record_data = struct.pack("<I", decompressed_size) + compressed
        return build_record(rec_type, form_id, record_data, flags=COMPRESSED_FLAG)

    def test_compressed_npc_full_replacement(self):
        """压缩的 NPC_ 记录中 FULL 子记录应能正确替换。"""
        sub = build_subrecord(b"FULL", null_terminated("Guard"))
        rec = self._build_compressed_record(b"NPC_", 0x00000300, sub)
        grup = build_grup(b"NPC_", rec)
        data = build_esm_file(grup)

        # parser 能读取
        records = parse_esm_bytes(data)
        assert len(records) == 1
        assert records[0].text == "Guard"
        assert records[0].record_id == "NPC_:00000300:FULL"

        # writer 能写回
        translations = {"NPC_:00000300:FULL": "守卫"}
        result = rewrite_esm_bytes(data, translations)

        # 验证写回后能正确读取
        result_records = parse_esm_bytes(result)
        assert len(result_records) == 1
        assert result_records[0].text == "守卫"

    def test_compressed_npc_shrt_replacement(self):
        """压缩的 NPC_ 记录中 SHRT 子记录应能正确替换。"""
        sub_full = build_subrecord(b"FULL", null_terminated("Captain"))
        sub_shrt = build_subrecord(b"SHRT", null_terminated("Cap"))
        rec = self._build_compressed_record(b"NPC_", 0x00000301, sub_full + sub_shrt)
        grup = build_grup(b"NPC_", rec)
        data = build_esm_file(grup)

        translations = {
            "NPC_:00000301:FULL": "队长",
            "NPC_:00000301:SHRT": "队",
        }
        result = rewrite_esm_bytes(data, translations)

        result_records = parse_esm_bytes(result)
        assert len(result_records) == 2
        texts = {r.record_id: r.text for r in result_records}
        assert texts["NPC_:00000301:FULL"] == "队长"
        assert texts["NPC_:00000301:SHRT"] == "队"

    def test_compressed_cell_full_replacement(self):
        """压缩的 CELL 记录中 FULL 子记录应能正确替换。"""
        sub = build_subrecord(b"FULL", null_terminated("New Atlantis"))
        rec = self._build_compressed_record(b"CELL", 0x00000400, sub)
        grup = build_grup(b"CELL", rec)
        data = build_esm_file(grup)

        translations = {"CELL:00000400:FULL": "新亚特兰蒂斯"}
        result = rewrite_esm_bytes(data, translations)

        result_records = parse_esm_bytes(result)
        assert len(result_records) == 1
        assert result_records[0].text == "新亚特兰蒂斯"

    def test_compressed_pndt_full_replacement(self):
        """压缩的 PNDT 记录中 FULL 子记录应能正确替换。"""
        sub = build_subrecord(b"FULL", null_terminated("Jemison"))
        rec = self._build_compressed_record(b"PNDT", 0x00000500, sub)
        grup = build_grup(b"PNDT", rec)
        data = build_esm_file(grup)

        translations = {"PNDT:00000500:FULL": "杰米森"}
        result = rewrite_esm_bytes(data, translations)

        result_records = parse_esm_bytes(result)
        assert len(result_records) == 1
        assert result_records[0].text == "杰米森"

    def test_compressed_no_translation_round_trip(self):
        """压缩记录无翻译时应保持可正确解析（往返一致性）。"""
        sub = build_subrecord(b"FULL", null_terminated("Test NPC"))
        rec = self._build_compressed_record(b"NPC_", 0x00000600, sub)
        grup = build_grup(b"NPC_", rec)
        data = build_esm_file(grup)

        result = rewrite_esm_bytes(data, {})

        # 重写后仍能正确解析
        result_records = parse_esm_bytes(result)
        assert len(result_records) == 1
        assert result_records[0].text == "Test NPC"

    def test_mixed_compressed_and_uncompressed(self):
        """混合压缩和非压缩记录应都能正确处理。"""
        # 压缩的 NPC_ 记录
        sub_npc = build_subrecord(b"FULL", null_terminated("Guard"))
        rec_npc = self._build_compressed_record(b"NPC_", 0x00000700, sub_npc)

        # 非压缩的 WEAP 记录
        sub_weap = build_subrecord(b"FULL", null_terminated("Sword"))
        rec_weap = build_record(b"WEAP", 0x00000701, sub_weap)

        grup_npc = build_grup(b"NPC_", rec_npc)
        grup_weap = build_grup(b"WEAP", rec_weap)
        data = build_esm_file(grup_npc + grup_weap)

        translations = {
            "NPC_:00000700:FULL": "守卫",
            "WEAP:00000701:FULL": "剑",
        }
        result = rewrite_esm_bytes(data, translations)

        result_records = parse_esm_bytes(result)
        assert len(result_records) == 2
        texts = {r.record_id: r.text for r in result_records}
        assert texts["NPC_:00000700:FULL"] == "守卫"
        assert texts["WEAP:00000701:FULL"] == "剑"


class TestNonPrintableSubrecordCounting:
    """非可打印子记录计数一致性测试（修复重复词条写回遗漏）。"""

    def test_non_printable_full_before_printable_full(self):
        """非可打印 FULL 子记录不应影响后续可打印 FULL 的 record_id 编号。

        Parser 只对可打印文本计数，Writer 必须保持一致。
        """
        # 第一个 FULL：二进制数据（不可打印，parser 会跳过）
        binary_data = b"\x80\x81\x82\x83\x00"
        sub_binary = build_subrecord(b"FULL", binary_data)

        # 第二个 FULL：正常文本（可打印，parser 会提取）
        sub_text = build_subrecord(b"FULL", null_terminated("Iron Sword"))

        rec = build_record(b"WEAP", 0x00000800, sub_binary + sub_text)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        # parser 应只提取一条记录，record_id 为 WEAP:00000800:FULL（count=0）
        records = parse_esm_bytes(data)
        assert len(records) == 1
        assert records[0].record_id == "WEAP:00000800:FULL"
        assert records[0].text == "Iron Sword"

        # writer 应能正确匹配并替换
        translations = {"WEAP:00000800:FULL": "铁剑"}
        result = rewrite_esm_bytes(data, translations)

        result_records = parse_esm_bytes(result)
        assert len(result_records) == 1
        assert result_records[0].text == "铁剑"
        assert result_records[0].record_id == "WEAP:00000800:FULL"

    def test_multiple_printable_with_non_printable_interleaved(self):
        """多个可打印 FULL 之间夹杂非可打印 FULL 时编号应正确。"""
        sub1 = build_subrecord(b"FULL", null_terminated("First"))
        sub_bin = build_subrecord(b"FULL", b"\xff\xfe\xfd\x00")
        sub2 = build_subrecord(b"FULL", null_terminated("Second"))

        rec = build_record(b"WEAP", 0x00000900, sub1 + sub_bin + sub2)
        grup = build_grup(b"WEAP", rec)
        data = build_esm_file(grup)

        records = parse_esm_bytes(data)
        assert len(records) == 2
        assert records[0].record_id == "WEAP:00000900:FULL"
        assert records[1].record_id == "WEAP:00000900:FULL#1"

        translations = {
            "WEAP:00000900:FULL": "第一",
            "WEAP:00000900:FULL#1": "第二",
        }
        result = rewrite_esm_bytes(data, translations)

        result_records = parse_esm_bytes(result)
        assert len(result_records) == 2
        texts = {r.record_id: r.text for r in result_records}
        assert texts["WEAP:00000900:FULL"] == "第一"
        assert texts["WEAP:00000900:FULL#1"] == "第二"
