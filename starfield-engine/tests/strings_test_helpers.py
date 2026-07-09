"""Strings 文件测试辅助函数，构建 .STRINGS/.DLSTRINGS/.ILSTRINGS 二进制数据。"""

from __future__ import annotations

import struct


def build_strings_file(entries: list[tuple[int, str]], length_prefixed: bool) -> bytes:
    """构建单个 Strings 文件字节。

    Args:
        entries: (string_id, text) 列表，按目录顺序排列。
        length_prefixed: 数据区是否带 uint32 长度前缀（.DLSTRINGS/.ILSTRINGS）。

    Returns:
        完整的 Strings 文件字节。
    """
    directory = b""
    data_section = b""
    for string_id, text in entries:
        raw = text.encode("utf-8") + b"\x00"
        offset = len(data_section)
        directory += struct.pack("<II", string_id, offset)
        if length_prefixed:
            data_section += struct.pack("<I", len(raw)) + raw
        else:
            data_section += raw
    header = struct.pack("<II", len(entries), len(data_section))
    return header + directory + data_section


def write_strings_dir_fixture(dir_path, base_name: str, strings=None, dlstrings=None, ilstrings=None) -> None:
    """在指定目录写出三个 Strings 文件（用于测试 parse_strings_dir/write_strings_dir）。

    Args:
        dir_path: 目标目录（pathlib.Path）。
        base_name: 文件基础名（不含扩展名），如 "mymod_zhhans"。
        strings: .strings 的 (id, text) 列表。
        dlstrings: .dlstrings 的 (id, text) 列表。
        ilstrings: .ilstrings 的 (id, text) 列表。
    """
    dir_path.mkdir(parents=True, exist_ok=True)
    (dir_path / f"{base_name}.strings").write_bytes(build_strings_file(strings or [], False))
    (dir_path / f"{base_name}.dlstrings").write_bytes(build_strings_file(dlstrings or [], True))
    (dir_path / f"{base_name}.ilstrings").write_bytes(build_strings_file(ilstrings or [], True))
