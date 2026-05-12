"""从 BA2 中提取 strings 文件并解析内容。"""

import struct
import sys
import zlib


def extract_ba2_files(ba2_path, target_patterns):
    """从 BA2 v2/v3 (GNRL) 中提取匹配的文件。返回 {filename: bytes}。"""
    with open(ba2_path, "rb") as f:
        magic = f.read(4)  # BTDX
        version = struct.unpack("<I", f.read(4))[0]
        archive_type = f.read(4)  # GNRL or DX10
        file_count = struct.unpack("<I", f.read(4))[0]
        name_table_offset = struct.unpack("<Q", f.read(8))[0]

        print(f"BA2: magic={magic} version={version} type={archive_type} files={file_count}")

        # 读取文件条目 (GNRL 格式)
        # v2/v3 GNRL entry: name_hash(4) + ext(4) + dir_hash(4) + unknown(4) + offset(8) + packed_size(4) + unpacked_size(4) + unknown(4)
        # 但 Starfield BA2 v2 的 GNRL entry 可能不同，先试标准格式
        entries = []
        for i in range(file_count):
            name_hash = struct.unpack("<I", f.read(4))[0]
            ext = f.read(4)
            dir_hash = struct.unpack("<I", f.read(4))[0]
            unknown1 = struct.unpack("<I", f.read(4))[0]
            offset = struct.unpack("<Q", f.read(8))[0]
            packed_size = struct.unpack("<I", f.read(4))[0]
            unpacked_size = struct.unpack("<I", f.read(4))[0]
            unknown2 = struct.unpack("<I", f.read(4))[0]
            entries.append((offset, packed_size, unpacked_size))

        # 读取文件名表
        f.seek(name_table_offset)
        names = []
        for i in range(file_count):
            name_len = struct.unpack("<H", f.read(2))[0]
            name = f.read(name_len).decode("utf-8", errors="replace")
            names.append(name)

        # 提取匹配的文件
        results = {}
        for i, name in enumerate(names):
            if any(p in name.lower() for p in target_patterns):
                offset, packed_size, unpacked_size = entries[i]
                f.seek(offset)
                if packed_size == 0:
                    # 未压缩
                    data = f.read(unpacked_size)
                else:
                    # zlib 压缩
                    compressed = f.read(packed_size)
                    data = zlib.decompress(compressed)
                results[name] = data
                print(f"  提取: {name} ({len(data)} bytes)")

        return results


def parse_strings(data, filename):
    """解析 .strings 文件格式。返回 [(string_id, text), ...]。"""
    if len(data) < 8:
        return []

    count = struct.unpack_from("<I", data, 0)[0]
    data_size = struct.unpack_from("<I", data, 4)[0]

    entries = []
    offset = 8
    for i in range(count):
        if offset + 8 > len(data):
            break
        string_id = struct.unpack_from("<I", data, offset)[0]
        str_offset = struct.unpack_from("<I", data, offset + 4)[0]
        entries.append((string_id, str_offset))
        offset += 8

    data_start = 8 + count * 8
    results = []

    is_dl_or_il = filename.endswith(".dlstrings") or filename.endswith(".ilstrings")

    for string_id, str_offset in entries:
        abs_offset = data_start + str_offset
        if abs_offset >= len(data):
            continue

        if is_dl_or_il:
            # dlstrings/ilstrings: 先读 4 字节长度，再读文本
            if abs_offset + 4 > len(data):
                continue
            str_len = struct.unpack_from("<I", data, abs_offset)[0]
            text_start = abs_offset + 4
            text_end = text_start + str_len
            if text_end > len(data):
                text_end = len(data)
            raw = data[text_start:text_end]
        else:
            # strings: null-terminated
            end = data.index(b"\x00", abs_offset) if b"\x00" in data[abs_offset:] else len(data)
            raw = data[abs_offset:end]

        if raw.endswith(b"\x00"):
            raw = raw[:-1]

        text = raw.decode("utf-8", errors="replace")
        results.append((string_id, text))

    return results


def main():
    ba2_path = sys.argv[1]
    lang = sys.argv[2] if len(sys.argv) > 2 else "zhhans"

    patterns = [f"_{lang}.strings", f"_{lang}.dlstrings", f"_{lang}.ilstrings"]
    files = extract_ba2_files(ba2_path, patterns)

    for filename, data in sorted(files.items()):
        print(f"\n{'='*60}")
        print(f"  {filename}")
        print(f"{'='*60}")

        entries = parse_strings(data, filename)
        print(f"  共 {len(entries)} 条")

        # 打印前 20 条
        for sid, text in entries[:20]:
            preview = text[:100].replace("\n", "\\n")
            has_cjk = any("\u4e00" <= c <= "\u9fff" for c in text)
            tag = " [中文]" if has_cjk else ""
            print(f"  {sid:08X}: {preview}{tag}")

        if len(entries) > 20:
            print(f"  ... 还有 {len(entries) - 20} 条")

        # 统计中文占比
        cjk_count = sum(1 for _, t in entries if any("\u4e00" <= c <= "\u9fff" for c in t))
        print(f"\n  中文条目: {cjk_count}/{len(entries)} ({cjk_count*100//max(len(entries),1)}%)")


if __name__ == "__main__":
    main()
