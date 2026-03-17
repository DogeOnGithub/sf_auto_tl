"""列出修复后仍保留在 Persistent 中的记录详情。

用法: python -m tools.list_kept_persistent <fixed_esm_path>
"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
INITIALLY_DISABLED_FLAG = 0x00000800
SUBRECORD_HEADER_SIZE = 6

TARGET_CELLS = {
    0x01009811, 0x01006B2C, 0x01007E32, 0x010068CE, 0x01007C0C,
}


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
        if st not in result:
            result[st] = data[offset:offset+ss]
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
            if gt == 8:
                nc["persistent"] = True
                nc["cell_fid"] = struct.unpack_from("<I", gl, 0)[0]
            elif gt == 9:
                nc["persistent"] = False
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

            cell_fid = ctx.get("cell_fid", 0)
            is_p = ctx.get("persistent", False)

            if is_p and cell_fid in TARGET_CELLS:
                rd = data[rs:re]
                if fl & COMPRESSED_FLAG and len(rd) >= 4:
                    try:
                        rd = zlib.decompress(rd[4:], bufsize=struct.unpack_from("<I", rd, 0)[0])
                    except zlib.error:
                        rd = b""
                subs = _parse_subs(rd)
                rts = rt.decode("ascii", errors="replace")
                base_fid = 0
                if b"NAME" in subs and len(subs[b"NAME"]) >= 4:
                    base_fid = struct.unpack_from("<I", subs[b"NAME"], 0)[0]
                edid = subs.get(b"EDID", b"").rstrip(b"\x00").decode("utf-8", errors="replace")
                disabled = bool(fl & INITIALLY_DISABLED_FLAG)
                reason = []
                if rts != "REFR":
                    reason.append(f"非REFR({rts})")
                if disabled:
                    reason.append("InitiallyDisabled")
                if base_fid and (base_fid >> 24) == 0x01:
                    reason.append(f"MOD自定义Base")
                results.append({
                    "fid": fid, "type": rts, "base": base_fid,
                    "edid": edid, "disabled": disabled,
                    "cell": cell_fid, "reason": " + ".join(reason),
                })
            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.list_kept_persistent <esm_path>")
        sys.exit(1)
    with open(sys.argv[1], "rb") as f:
        data = f.read()
    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs
    results = []
    scan(data, start, len(data), {}, results)
    print(f"目标 Cell 中保留在 Persistent 的记录: {len(results)} 条\n")
    for r in results:
        print(f"  {r['fid']:08X}  type={r['type']}  base={r['base']:08X}  "
              f"edid={r['edid'] or '(无)'}  保留原因: {r['reason']}")


if __name__ == "__main__":
    main()
