"""Strings 解析器单元测试。"""

import struct

from engine.strings_parser import (
    build_strings_record_id,
    find_strings_file,
    parse_strings_dir,
    parse_strings_file,
)
from tests.strings_test_helpers import build_strings_file, write_strings_dir_fixture


class TestParseStringsFile:
    """单文件解析测试。"""

    def test_parse_null_terminated(self):
        """.STRINGS 格式（无长度前缀）解析。"""
        data = build_strings_file([(1, "Hello"), (2, "World")], length_prefixed=False)
        entries = parse_strings_file(data, length_prefixed=False)
        assert entries == [(1, "Hello"), (2, "World")]

    def test_parse_length_prefixed(self):
        """.DLSTRINGS/.ILSTRINGS 格式（带长度前缀）解析。"""
        data = build_strings_file([(10, "A description."), (20, "Another one.")], length_prefixed=True)
        entries = parse_strings_file(data, length_prefixed=True)
        assert entries == [(10, "A description."), (20, "Another one.")]

    def test_parse_empty_file(self):
        """count=0 的空文件返回空列表。"""
        data = build_strings_file([], length_prefixed=True)
        assert len(data) == 8
        assert parse_strings_file(data, length_prefixed=True) == []

    def test_parse_preserves_order_and_ids(self):
        """保留目录顺序与非连续的 string_id。"""
        data = build_strings_file([(100, "a"), (5, "b"), (77, "c")], length_prefixed=False)
        entries = parse_strings_file(data, length_prefixed=False)
        assert [e[0] for e in entries] == [100, 5, 77]

    def test_parse_utf8_text(self):
        """UTF-8 中文文本正确解码。"""
        data = build_strings_file([(1, "铁剑")], length_prefixed=False)
        entries = parse_strings_file(data, length_prefixed=False)
        assert entries[0] == (1, "铁剑")

    def test_shared_offset_entries(self):
        """多个 ID 指向同一 offset（去重结构）应各自解析出相同文本。"""
        # 手工构造两个目录条目指向同一 offset
        directory = struct.pack("<II", 1, 0) + struct.pack("<II", 2, 0)
        data_section = "Shared".encode("utf-8") + b"\x00"
        header = struct.pack("<II", 2, len(data_section))
        data = header + directory + data_section
        entries = parse_strings_file(data, length_prefixed=False)
        assert entries == [(1, "Shared"), (2, "Shared")]


class TestParseStringsDir:
    """目录解析测试。"""

    def test_parse_dir_all_three_files(self, tmp_path):
        """解析三个文件并生成正确的 record_id。"""
        write_strings_dir_fixture(
            tmp_path,
            "mymod_zhhans",
            strings=[(1, "Sword"), (2, "Shield")],
            dlstrings=[(10, "A blade.")],
            ilstrings=[(100, "Dialogue line.")],
        )
        records = parse_strings_dir(str(tmp_path))
        record_ids = {r.record_id for r in records}
        assert record_ids == {
            "STRINGS:1:STR",
            "STRINGS:2:STR",
            "DLSTRINGS:10:DL",
            "ILSTRINGS:100:IL",
        }

    def test_skip_empty_text(self, tmp_path):
        """空串与纯空白不作为可翻译记录提取。"""
        write_strings_dir_fixture(
            tmp_path,
            "mymod_zhhans",
            strings=[(1, "Real"), (2, ""), (3, "   ")],
        )
        records = parse_strings_dir(str(tmp_path))
        assert [r.record_id for r in records] == ["STRINGS:1:STR"]

    def test_empty_ilstrings(self, tmp_path):
        """空 .ilstrings 不产生记录且不报错。"""
        write_strings_dir_fixture(
            tmp_path,
            "mymod_zhhans",
            strings=[(1, "X")],
            ilstrings=[],
        )
        records = parse_strings_dir(str(tmp_path))
        assert [r.record_id for r in records] == ["STRINGS:1:STR"]

    def test_missing_file_tolerated(self, tmp_path):
        """缺少某个文件时跳过，不影响其他文件解析。"""
        # 只写 .strings
        (tmp_path / "mymod_zhhans.strings").write_bytes(
            build_strings_file([(1, "Only")], length_prefixed=False)
        )
        records = parse_strings_dir(str(tmp_path))
        assert [r.record_id for r in records] == ["STRINGS:1:STR"]


class TestHelpers:
    """辅助函数测试。"""

    def test_build_record_id(self):
        assert build_strings_record_id("STRINGS", 42, "STR") == "STRINGS:42:STR"

    def test_find_strings_file_case_insensitive(self, tmp_path):
        """大写扩展名也能匹配。"""
        (tmp_path / "MOD_ZHHANS.STRINGS").write_bytes(build_strings_file([(1, "x")], False))
        found = find_strings_file(tmp_path, ".strings")
        assert found is not None
        assert found.name == "MOD_ZHHANS.STRINGS"
