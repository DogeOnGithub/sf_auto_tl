"""列出 ESM 文件中所有 Worldspace 的详细信息（EDID + FULL 显示名称）。

用法: python -m tools.list_worldspaces <esm_file_path>
"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _parse_wrld_subrecords(data: bytes) -> dict:
    result = {"edid": "", "full": ""}
    offset = 0
    while offset + SUBRECORD_HEADER_SIZE <= len(data):
        sub_type = data[offset:offset + 4]
        sub_size = struct.unpack_from("<H", data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE
        if offset + sub_size > len(data):
            break
        sub_data = data[offset:offset + sub_size]
        if sub_type == b"EDID" and sub_size > 0:
            result["edid"] = sub_data.rstrip(b"\x00").decode("utf-8", errors="replace")
        elif sub_type == b"FULL" and sub_size > 0:
            result["full"] = sub_data.rstrip(b"\x00").decode("utf-8", errors="replace")
        offset += sub_size
    return result


def scan_wrld(data, offset, end, worldspaces):
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
            scan_wrld(data, offset + GRUP_HEADER_SIZE, group_end, worldspaces)
            offset = group_end
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = min(rec_start + data_size, end)

            if rec_type == b"WRLD":
                rec_data = data[rec_start:rec_end]
                if flags & COMPRESSED_FLAG and len(rec_data) >= 4:
                    try:
                        rec_data = zlib.decompress(rec_data[4:], bufsize=struct.unpack_from("<I", rec_data, 0)[0])
                    except zlib.error:
                        rec_data = b""
                info = _parse_wrld_subrecords(rec_data)
                worldspaces.append({"form_id": form_id, **info})

            offset = rec_end


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.list_worldspaces <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    with open(file_path, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    worldspaces = []
    scan_wrld(data, start, len(data), worldspaces)

    print(f"共 {len(worldspaces)} 个 Worldspace:\n")
    for w in worldspaces:
        full = w["full"] or "(无显示名称)"
        print(f"  {w['form_id']:08X}  EDID={w['edid']:<25}  FULL={full}")


if __name__ == "__main__":
    main()
