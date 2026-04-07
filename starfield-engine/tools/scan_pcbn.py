"""分析 PCBN 记录的内容，看看它引用了哪些 REFR。

PCBN = PreCombined (预合并) 数据，包含一组被合并渲染的 REFR 引用。

用法: python -m tools.scan_pcbn <esm_file>
"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def scan(data: bytes, offset: int, end: int, ctx: dict, results: dict):
    while offset < end:
        if offset + 4 > end:
            break
        rt = data[offset:offset + 4]
        if rt == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            gs = struct.unpack_from("<I", data, offset + 4)[0]
            gt = struct.unpack_from("<I", data, offset + 12)[0]
            gl = data[offset + 8:offset + 12]
            if gs < GRUP_HEADER_SIZE:
                break
            ge = min(offset + gs, end)
            nc = dict(ctx)
            if gt == 6:
                nc["cell_fid"] = struct.unpack_from("<I", gl, 0)[0]
            elif gt == 8:
                nc["persistent"] = True
            elif gt == 9:
                nc["persistent"] = False
            scan(data, offset + GRUP_HEADER_SIZE, ge, nc, results)
            offset = ge
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            ds = struct.unpack_from("<I", data, offset + 4)[0]
            fl = struct.unpack_from("<I", data, offset + 8)[0]
            fid = struct.unpack_from("<I", data, offset + 12)[0]
            rs = offset + RECORD_HEADER_SIZE
            re = min(rs + ds, end)

            if rt in (b"PCBN", b"PCCN", b"RFGP"):
                rec_data = data[rs:re]
                if fl & COMPRESSED_FLAG and len(rec_data) >= 4:
                    try:
                        rec_data = zlib.decompress(
                            rec_data[4:],
                            bufsize=struct.unpack_from("<I", rec_data, 0)[0],
                        )
                    except zlib.error:
                        rec_data = b""

                # 解析子记录
                subs = []
                pos = 0
                xxxx_size = None
                while pos + SUBRECORD_HEADER_SIZE <= len(rec_data):
                    st = rec_data[pos:pos + 4]
                    ss = struct.unpack_from("<H", rec_data, pos + 4)[0]
                    pos += SUBRECORD_HEADER_SIZE
                    if st == b"XXXX":
                        if ss == 4 and pos + 4 <= len(rec_data):
                            xxxx_size = struct.unpack_from("<I", rec_data, pos)[0]
                        pos += ss
                        continue
                    if xxxx_size is not None:
                        ss = xxxx_size
                        xxxx_size = None
                    if pos + ss > len(rec_data):
                        break
                    subs.append((st, rec_data[pos:pos + ss]))
                    pos += ss

                rtype = rt.decode("ascii", errors="replace")
                is_vanilla = (fid >> 24) == 0x00
                results.setdefault(rtype, []).append({
                    "fid": fid,
                    "flags": fl,
                    "size": ds,
                    "is_vanilla": is_vanilla,
                    "cell_fid": ctx.get("cell_fid", 0),
                    "persistent": ctx.get("persistent"),
                    "subs": subs,
                    "raw_size": len(rec_data),
                })

            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.scan_pcbn <esm_file>")
        sys.exit(1)

    with open(sys.argv[1], "rb") as f:
        data = f.read()

    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs

    results = {}
    scan(data, start, len(data), {}, results)

    for rtype in ("PCBN", "PCCN", "RFGP"):
        records = results.get(rtype, [])
        if not records:
            continue
        print(f"\n=== {rtype}: {len(records)} 条 ===")
        for r in records[:30]:
            tag = "原版" if r["is_vanilla"] else "MOD"
            p_tag = "P" if r.get("persistent") else ("T" if r.get("persistent") is False else "?")
            print(f"\n  {r['fid']:08X} [{tag}] cell={r['cell_fid']:08X} [{p_tag}] "
                  f"flags={r['flags']:08X} raw={r['raw_size']}b")
            for st, sd in r["subs"]:
                stn = st.decode("ascii", errors="replace")
                if len(sd) == 4:
                    val = struct.unpack_from("<I", sd, 0)[0]
                    print(f"    {stn}: {val:08X}")
                elif len(sd) <= 64:
                    print(f"    {stn}: [{len(sd)}b] {sd.hex()}")
                else:
                    print(f"    {stn}: [{len(sd)}b]")
        if len(records) > 30:
            print(f"\n  ... 还有 {len(records) - 30} 条")


if __name__ == "__main__":
    main()
