"""深入分析 MOD Worldspace 的详细信息，包括父 Worldspace 关联。

用法: python -m tools.scan_worldspace_detail <esm_file>
"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _parse_all_subs(data: bytes, flags: int) -> list:
    """解析记录的所有子记录，返回列表（保留重复）。"""
    rec_data = data
    if flags & COMPRESSED_FLAG:
        if len(rec_data) < 4:
            return []
        try:
            rec_data = zlib.decompress(
                rec_data[4:],
                bufsize=struct.unpack_from("<I", rec_data, 0)[0],
            )
        except zlib.error:
            return []
    result = []
    offset = 0
    xxxx_size = None
    while offset + SUBRECORD_HEADER_SIZE <= len(rec_data):
        sub_type = rec_data[offset:offset + 4]
        sub_size = struct.unpack_from("<H", rec_data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE
        if sub_type == b"XXXX":
            if sub_size == 4 and offset + 4 <= len(rec_data):
                xxxx_size = struct.unpack_from("<I", rec_data, offset)[0]
            offset += sub_size
            continue
        if xxxx_size is not None:
            sub_size = xxxx_size
            xxxx_size = None
        if offset + sub_size > len(rec_data):
            break
        result.append((sub_type, rec_data[offset:offset + sub_size]))
        offset += sub_size
    return result


def scan_records(data: bytes, offset: int, end: int, results: dict):
    """扫描所有记录。"""
    while offset < end:
        if offset + 4 > end:
            break
        rt = data[offset:offset + 4]
        if rt == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            gs = struct.unpack_from("<I", data, offset + 4)[0]
            if gs < GRUP_HEADER_SIZE:
                break
            ge = min(offset + gs, end)
            scan_records(data, offset + GRUP_HEADER_SIZE, ge, results)
            offset = ge
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            ds = struct.unpack_from("<I", data, offset + 4)[0]
            fl = struct.unpack_from("<I", data, offset + 8)[0]
            fid = struct.unpack_from("<I", data, offset + 12)[0]
            rs = offset + RECORD_HEADER_SIZE
            re = min(rs + ds, end)

            if rt == b"WRLD":
                subs = _parse_all_subs(data[rs:re], fl)
                results.setdefault("wrld", []).append({
                    "fid": fid, "flags": fl, "subs": subs,
                })

            if rt == b"NAVI":
                results.setdefault("navi", []).append({
                    "fid": fid, "flags": fl, "size": ds,
                })

            if rt == b"PCBN":
                results.setdefault("pcbn", []).append({
                    "fid": fid, "flags": fl, "size": ds,
                })

            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.scan_worldspace_detail <esm_file>")
        sys.exit(1)

    path = sys.argv[1]
    with open(path, "rb") as f:
        data = f.read()

    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs

    results = {}
    scan_records(data, start, len(data), results)

    # 详细输出 WRLD 记录
    for w in results.get("wrld", []):
        print(f"\n=== WRLD {w['fid']:08X} (flags={w['flags']:08X}) ===")
        for sub_type, sub_data in w["subs"]:
            st = sub_type.decode("ascii", errors="replace")
            if sub_type in (b"EDID", b"FULL"):
                text = sub_data.rstrip(b"\x00").decode("utf-8", errors="replace")
                print(f"  {st}: {text}")
            elif len(sub_data) == 4:
                val = struct.unpack_from("<I", sub_data, 0)[0]
                print(f"  {st}: {val:08X} (uint32) / {struct.unpack_from('<i', sub_data, 0)[0]} (int32)")
            elif len(sub_data) == 2:
                val = struct.unpack_from("<H", sub_data, 0)[0]
                print(f"  {st}: {val:04X} (uint16)")
            elif len(sub_data) == 1:
                print(f"  {st}: {sub_data[0]:02X}")
            elif len(sub_data) <= 32:
                print(f"  {st}: [{len(sub_data)} bytes] {sub_data.hex()}")
            else:
                print(f"  {st}: [{len(sub_data)} bytes]")

    # NAVI 和 PCBN
    for n in results.get("navi", []):
        print(f"\nNAVI {n['fid']:08X}: size={n['size']} bytes, flags={n['flags']:08X}")
        tag = "原版覆盖" if (n['fid'] >> 24) == 0x00 else "MOD新增"
        print(f"  [{tag}]")

    for p in results.get("pcbn", []):
        print(f"\nPCBN {p['fid']:08X}: size={p['size']} bytes, flags={p['flags']:08X}")
        tag = "原版覆盖" if (p['fid'] >> 24) == 0x00 else "MOD新增"
        print(f"  [{tag}]")


if __name__ == "__main__":
    main()
