"""Strings 重组器单元测试。"""

import struct

from engine.strings_parser import find_strings_file, parse_strings_file
from engine.strings_writer import write_strings_dir
from tests.strings_test_helpers import build_strings_file, write_strings_dir_fixture


def _read_entries(path, length_prefixed):
    """读取并解析一个 Strings 文件的 (id, text) 列表。"""
    return parse_strings_file(path.read_bytes(), length_prefixed)


class TestWriteStringsDir:
    """目录回写测试。"""

    def test_translations_applied(self, tmp_path):
        """译文正确写入，保留原始文件名。"""
        src = tmp_path / "in"
        write_strings_dir_fixture(
            src,
            "mymod_zhhans",
            strings=[(1, "Sword"), (2, "Shield")],
            dlstrings=[(10, "A blade.")],
        )
        translations = {
            "STRINGS:1:STR": "剑",
            "STRINGS:2:STR": "盾",
            "DLSTRINGS:10:DL": "一把刀。",
        }
        out = tmp_path / "out"
        result = write_strings_dir(str(src), translations, str(out))

        assert result.file_count == 3
        s = _read_entries(out / "mymod_zhhans.strings", False)
        assert s == [(1, "剑"), (2, "盾")]
        dl = _read_entries(out / "mymod_zhhans.dlstrings", True)
        assert dl == [(10, "一把刀。")]

    def test_ids_preserved(self, tmp_path):
        """回写后 string_id 与顺序完全保留。"""
        src = tmp_path / "in"
        write_strings_dir_fixture(
            src, "m_zhhans", strings=[(100, "a"), (5, "b"), (77, "c")]
        )
        out = tmp_path / "out"
        write_strings_dir(str(src), {"STRINGS:100:STR": "甲"}, str(out))
        entries = _read_entries(out / "m_zhhans.strings", False)
        assert [e[0] for e in entries] == [100, 5, 77]
        # 未翻译的按原文保留
        assert entries[1] == (5, "b")
        assert entries[2] == (77, "c")

    def test_untranslated_fallback_to_original(self, tmp_path):
        """未提供译文的条目按原文保留。"""
        src = tmp_path / "in"
        write_strings_dir_fixture(src, "m_zhhans", strings=[(1, "keep me")])
        out = tmp_path / "out"
        write_strings_dir(str(src), {}, str(out))
        entries = _read_entries(out / "m_zhhans.strings", False)
        assert entries == [(1, "keep me")]

    def test_empty_ilstrings_preserved(self, tmp_path):
        """空 .ilstrings 回写后仍为合法空文件（8 字节头）。"""
        src = tmp_path / "in"
        write_strings_dir_fixture(src, "m_zhhans", strings=[(1, "x")], ilstrings=[])
        out = tmp_path / "out"
        write_strings_dir(str(src), {"STRINGS:1:STR": "叉"}, str(out))
        il = (out / "m_zhhans.ilstrings").read_bytes()
        assert len(il) == 8
        count, data_size = struct.unpack_from("<II", il, 0)
        assert count == 0 and data_size == 0

    def test_header_datasize_consistent(self, tmp_path):
        """回写后 header 中 data_size 与实际数据区长度一致。"""
        src = tmp_path / "in"
        write_strings_dir_fixture(
            src, "m_zhhans", strings=[(1, "aaa"), (2, "bbbb")], dlstrings=[(3, "cc")]
        )
        out = tmp_path / "out"
        write_strings_dir(str(src), {"STRINGS:1:STR": "中文更长的译文"}, str(out))
        for name, lp in [("m_zhhans.strings", False), ("m_zhhans.dlstrings", True)]:
            data = (out / name).read_bytes()
            count, data_size = struct.unpack_from("<II", data, 0)
            assert 8 + count * 8 + data_size == len(data)

    def test_roundtrip_translate_reparse(self, tmp_path):
        """写入译文后重新解析应得到译文。"""
        src = tmp_path / "in"
        write_strings_dir_fixture(
            src,
            "m_zhhans",
            strings=[(1, "Purchase"), (2, "Unlock")],
            dlstrings=[(10, "Bulk storage.")],
        )
        out = tmp_path / "out"
        translations = {
            "STRINGS:1:STR": "购买",
            "STRINGS:2:STR": "解锁",
            "DLSTRINGS:10:DL": "批量存储。",
        }
        write_strings_dir(str(src), translations, str(out))

        s = dict(_read_entries(out / "m_zhhans.strings", False))
        dl = dict(_read_entries(out / "m_zhhans.dlstrings", True))
        assert s[1] == "购买"
        assert s[2] == "解锁"
        assert dl[10] == "批量存储。"

    def test_output_filename_matches_input(self, tmp_path):
        """输出文件名（含大小写与 _zhhans 后缀）与输入一致。"""
        src = tmp_path / "in"
        src.mkdir()
        (src / "MyMod_zhhans.STRINGS").write_bytes(build_strings_file([(1, "x")], False))
        (src / "MyMod_zhhans.DLSTRINGS").write_bytes(build_strings_file([], True))
        (src / "MyMod_zhhans.ILSTRINGS").write_bytes(build_strings_file([], True))
        out = tmp_path / "out"
        write_strings_dir(str(src), {"STRINGS:1:STR": "叉"}, str(out))
        assert find_strings_file(out, ".strings").name == "MyMod_zhhans.STRINGS"
