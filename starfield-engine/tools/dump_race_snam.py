"""快速查找 ESM 中 RACE 记录的 SNAM 子记录内容。"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def decode_text(data):
    if data.endswith(b"\x00"):
        data = data[:-1]
    text = data.decode("utf-8", errors="replace")
    if "\ufffd" in text:
        text = data.decode("windows-1252", errors="replace")
    return text


def scan_subrecords(data, record_type, form_id, results):
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
            editor_id = decode_text(data[offset:offset + sub_size])

        # 打印 RACE 下所有含可读文本的子记录
        if record_type == b"RACE" and sub_size > 0:
            raw = data[offset:offset + sub_size]
            if raw.endswith(b"\x00"):
                raw_stripped = raw[:-1]
            else:
                raw_stripped = raw
            if len(raw_stripped) >= 2:
                try:
                    text = raw_stripped.decode("utf-8")
                    printable = sum(1 for c in text if c.isprintable() or c in ("\n", "\r", "\t"))
                    if len(text) > 0 and printable / len(text) >= 0.8:
                        sub_str = sub_type.decode("ascii", errors="replace")
                        key = (sub_str, )
                        results.setdefault(key, []).append((form_id, editor_id, text, sub_size))
                except UnicodeDecodeError:
                    pass

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
        print("该 ESM 中没有找到 RACE 记录的可读文本子记录")
        return

    for (sub_type,), entries in sorted(results.items()):
        print(f"\n  RACE -> {sub_type}  ({len(entries)} 条)")
        for form_id, editor_id, text, size in entries[:10]:
            eid = f"  [{editor_id}]" if editor_id else ""
            print(f"    {form_id:08X}{eid} ({size}B): {text[:100]}")
        if len(entries) > 10:
            print(f"    ... 还有 {len(entries) - 10} 条")


if __name__ == "__main__":
    main()
