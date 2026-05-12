"""Dump ALL subrecords from RACE records, showing raw hex + text attempt."""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def scan_subrecords(data, record_type, form_id, results):
    if record_type != b"RACE":
        return
    offset = 0
    editor_id = ""
    xxxx_size = None
    while offset + SUBRECORD_HEADER_SIZE <= len(data):
        sub_type = data[offset:offset + 4]
        sub_size = struct.unpack_from("<H", data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE

        if sub_type == b"XXXX":
            if sub_size == 4 and offset + 4 <= len(data):
                xxxx_size = struct.unpack_from("<I", data, offset)[0]
            offset += sub_size
            continue

        if xxxx_size is not None:
            sub_size = xxxx_size
            xxxx_size = None

        if offset + sub_size > len(data):
            break

        if sub_type == b"EDID" and sub_size > 0 and not editor_id:
            raw = data[offset:offset + sub_size]
            if raw.endswith(b"\x00"):
                raw = raw[:-1]
            editor_id = raw.decode("utf-8", errors="replace")

        sub_str = sub_type.decode("ascii", errors="replace")
        raw = data[offset:offset + sub_size]

        # 尝试解码文本
        text_attempt = None
        if sub_size > 0:
            raw_stripped = raw[:-1] if raw.endswith(b"\x00") else raw
            if len(raw_stripped) > 0:
                try:
                    t = raw_stripped.decode("utf-8")
                    text_attempt = t
                except UnicodeDecodeError:
                    try:
                        t = raw_stripped.decode("windows-1252")
                        text_attempt = t
                    except Exception:
                        pass

        results.setdefault(form_id, {"editor_id": editor_id, "subs": []})
        results[form_id]["editor_id"] = editor_id
        results[form_id]["subs"].append((sub_str, sub_size, raw[:32], text_attempt))

        offset += sub_size


def scan_records(data, offset, end, results):
    while offset < end:
        if offset + 4 > end:
            break
        rec_type = data[offset:offset + 4]
        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            if group_size < GRUP_HEADER_SIZE:
                break
            group_end = min(offset + group_size, end)
            scan_records(data, offset + GRUP_HEADER_SIZE, group_end, results)
            offset = group_end
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = rec_start + data_size
            if rec_end > end:
                break
            rec_data = data[rec_start:rec_end]
            if flags & COMPRESSED_FLAG:
                if len(rec_data) >= 4:
                    decomp_size = struct.unpack_from("<I", rec_data, 0)[0]
                    try:
                        rec_data = zlib.decompress(rec_data[4:], bufsize=decomp_size)
                    except zlib.error:
                        offset = rec_end
                        continue
            scan_subrecords(rec_data, rec_type, form_id, results)
            offset = rec_end


def main():
    file_path = sys.argv[1]
    with open(file_path, "rb") as f:
        data = f.read()

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    results = {}
    scan_records(data, start, len(data), results)

    if not results:
        print("该 ESM 中没有 RACE 记录")
        return

    print(f"找到 {len(results)} 条 RACE 记录\n")

    for form_id, info in sorted(results.items()):
        eid = info["editor_id"]
        print(f"RACE {form_id:08X}  [{eid}]")
        # 只打印 SNAM 和 FULL 相关的，以及统计其他类型
        sub_counts = {}
        snam_entries = []
        full_entries = []
        for sub_str, sub_size, raw_head, text in info["subs"]:
            sub_counts[sub_str] = sub_counts.get(sub_str, 0) + 1
            if sub_str == "SNAM":
                snam_entries.append((sub_size, raw_head, text))
            elif sub_str == "FULL":
                full_entries.append((sub_size, raw_head, text))

        if full_entries:
            print(f"  FULL ({len(full_entries)} 条):")
            for size, raw, text in full_entries:
                print(f"    {size}B  text={text!r}")

        if snam_entries:
            print(f"  SNAM ({len(snam_entries)} 条):")
            for size, raw, text in snam_entries[:20]:
                hex_str = raw[:min(size, 16)].hex(" ")
                print(f"    {size}B  hex=[{hex_str}]  text={text!r}")
            if len(snam_entries) > 20:
                print(f"    ... 还有 {len(snam_entries) - 20} 条")
        else:
            print("  (无 SNAM)")

        # 子记录类型汇总
        print(f"  子记录类型: {dict(sorted(sub_counts.items()))}")
        print()


if __name__ == "__main__":
    main()
