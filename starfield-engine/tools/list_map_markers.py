"""列出 ESM 文件中所有地图标记（REFR 的 FULL/UNAM）和 Location 记录名称。

用法: python -m tools.list_map_markers <esm_file_path>
"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _decode(data):
    return data.rstrip(b"\x00").decode("utf-8", errors="replace")


def _parse_subs(data):
    result = {}
    offset = 0
    xxxx_size = None
    while offset + 6 <= len(data):
        st = data[offset:offset+4]
        ss = struct.unpack_from("<H", data, offset+4)[0]
        offset += 6
        if st == b"XXXX":
            if ss == 4 and offset + 4 <= len(data):
                xxxx_size = struct.unpack_from("<I", data, offset)[0]
            offset += ss
            continue
        if xxxx_size is not None:
            ss = xxxx_size
            xxxx_size = None
        if offset + ss > len(data):
            break
        sd = data[offset:offset+ss]
        key = st.decode("ascii", errors="replace")
        if key not in result and ss > 0:
            result[key] = sd
        offset += ss
    return result


def scan(data, offset, end, ctx, results):
    while offset < end:
        if offset + 4 > end:
            break
        rt = data[offset:offset+4]
        if rt == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            gs = struct.unpack_from("<I", data, offset+4)[0]
            gt = struct.unpack_from("<I", data, offset+12)[0]
            gl = data[offset+8:offset+12]
            if gs < GRUP_HEADER_SIZE:
                break
            ge = min(offset + gs, end)
            nc = dict(ctx)
            if gt == 0:
                nc["top"] = gl.decode("ascii", errors="replace").strip("\x00")
            elif gt == 1:
                nc["wrld"] = struct.unpack_from("<I", gl, 0)[0]
            scan(data, offset + GRUP_HEADER_SIZE, ge, nc, results)
            offset = ge
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            ds = struct.unpack_from("<I", data, offset+4)[0]
            fl = struct.unpack_from("<I", data, offset+8)[0]
            fid = struct.unpack_from("<I", data, offset+12)[0]
            rs = offset + RECORD_HEADER_SIZE
            re = min(rs + ds, end)
            rd = data[rs:re]
            if fl & COMPRESSED_FLAG and len(rd) >= 4:
                try:
                    rd = zlib.decompress(rd[4:], bufsize=struct.unpack_from("<I", rd, 0)[0])
                except zlib.error:
                    rd = b""

            rts = rt.decode("ascii", errors="replace")

            if rts == "LCTN":
                subs = _parse_subs(rd)
                edid = _decode(subs["EDID"]) if "EDID" in subs else ""
                full = _decode(subs["FULL"]) if "FULL" in subs else ""
                if edid or full:
                    results["locations"].append({"fid": fid, "edid": edid, "full": full})

            elif rts == "REFR":
                subs = _parse_subs(rd)
                # Map Marker 有 FULL 或 UNAM
                full = _decode(subs["FULL"]) if "FULL" in subs else ""
                unam = _decode(subs["UNAM"]) if "UNAM" in subs else ""
                if full or unam:
                    wrld = ctx.get("wrld", 0)
                    results["markers"].append({
                        "fid": fid, "full": full, "unam": unam, "wrld": wrld
                    })

            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.list_map_markers <esm_file_path>")
        sys.exit(1)

    fp = sys.argv[1]
    with open(fp, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件"); sys.exit(1)

    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs

    results = {"locations": [], "markers": []}
    scan(data, start, len(data), {}, results)

    wrld_names = {
        0x0100308A: "COCOPOI08",
        0x010068CD: "COCOPOI9",
        0x01006B2B: "COCOPOI10",
        0x01007C0B: "COCOPOI11",
        0x01007E31: "COCOPOI12",
        0x01009810: "COCOPOI08Small",
    }

    print(f"=== Location 记录 ({len(results['locations'])}) ===\n")
    for loc in results["locations"]:
        print(f"  {loc['fid']:08X}  EDID={loc['edid']:<40}  FULL={loc['full']}")

    print(f"\n=== 有名称的 REFR (地图标记等) ({len(results['markers'])}) ===\n")
    for m in results["markers"]:
        wrld = wrld_names.get(m["wrld"], f"{m['wrld']:08X}" if m["wrld"] else "Interior")
        name = m["full"] or m["unam"]
        tag = "FULL" if m["full"] else "UNAM"
        print(f"  {m['fid']:08X}  [{wrld:<16}]  {tag}={name}")


if __name__ == "__main__":
    main()
