"""快速提取指定 record_type + subrecord_type 组合的文本样本。"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6

# 要查看的组合
TARGET_COMBINATIONS = {
    (b"TMLM", b"INAM"),
    (b"TMLM", b"ISTX"),
    (b"ACTI", b"ATTX"),
    (b"BOOK", b"ENAM"),
    (b"BOOK", b"FNAM"),
    (b"KYWD", b"ENAM"),
}


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

        if (record_type, sub_type) in TARGET_COMBINATIONS and sub_size > 0:
            text = decode_text(data[offset:offset + sub_size])
            if text:
                key = (record_type.decode("ascii"), sub_type.decode("ascii"))
                results.setdefault(key, []).append((form_id, editor_id, text))

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

    for (rec_type, sub_type), entries in sorted(results.items()):
        print(f"\n{'='*60}")
        print(f"  {rec_type} -> {sub_type}  ({len(entries)} 条)")
        print(f"{'='*60}")
        for form_id, editor_id, text in entries:
            eid = f"  [{editor_id}]" if editor_id else ""
            print(f"  {form_id:08X}{eid}")
            for line in text.split("\n"):
                print(f"    {line}")


if __name__ == "__main__":
    main()
