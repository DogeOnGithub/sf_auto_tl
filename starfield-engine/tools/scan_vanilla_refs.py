"""扫描 MOD 的 ESM 文件，找出所有引用或修改原版记录的情况。

重点关注：
1. MOD 是否覆盖了原版的记录（FormID 以 00 开头）
2. MOD 是否在原版 Worldspace 的 Cell 里放置了 REFR
3. 所有 REFR 的 Worldspace 归属关系

用法: python -m tools.scan_vanilla_refs <esm_file>
"""

import struct
import sys
import zlib

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _get_subrecords(data: bytes, flags: int) -> dict:
    """解析记录的子记录。"""
    rec_data = data
    if flags & COMPRESSED_FLAG:
        if len(rec_data) < 4:
            return {}
        try:
            rec_data = zlib.decompress(
                rec_data[4:],
                bufsize=struct.unpack_from("<I", rec_data, 0)[0],
            )
        except zlib.error:
            return {}
    result = {}
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
        if sub_type not in result:
            result[sub_type] = rec_data[offset:offset + sub_size]
        offset += sub_size
    return result


def scan(data: bytes, offset: int, end: int, ctx: dict, results: dict):
    """递归扫描所有记录。"""
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

            if gt == 0:
                nc["top_grup"] = gl.decode("ascii", errors="replace").strip("\x00")
            elif gt == 1:
                fid = struct.unpack_from("<I", gl, 0)[0]
                nc["worldspace_fid"] = fid
            elif gt == 6:
                fid = struct.unpack_from("<I", gl, 0)[0]
                nc["cell_fid"] = fid
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
            rtype = rt.decode("ascii", errors="replace")

            # 记录所有原版 FormID 的覆盖
            if (fid >> 24) == 0x00 and fid != 0:
                results.setdefault("vanilla_overrides", []).append({
                    "fid": fid, "type": rtype, "ctx": dict(ctx),
                })

            # 记录 WRLD 定义
            if rt == b"WRLD":
                subs = _get_subrecords(data[rs:re], fl)
                edid = subs.get(b"EDID", b"").rstrip(b"\x00").decode("utf-8", errors="replace")
                full = subs.get(b"FULL", b"").rstrip(b"\x00").decode("utf-8", errors="replace")
                results.setdefault("worldspaces", {})[fid] = {
                    "edid": edid, "full": full, "is_vanilla": (fid >> 24) == 0x00,
                }

            # 记录 CELL 定义
            if rt == b"CELL":
                subs = _get_subrecords(data[rs:re], fl)
                edid = subs.get(b"EDID", b"").rstrip(b"\x00").decode("utf-8", errors="replace")
                ws = ctx.get("worldspace_fid")
                results.setdefault("cells", {})[fid] = {
                    "edid": edid, "worldspace": ws,
                    "is_vanilla_cell": (fid >> 24) == 0x00,
                    "is_vanilla_ws": ws is not None and (ws >> 24) == 0x00,
                }

            # 记录 REFR 在原版 Worldspace 中的情况
            if rt == b"REFR":
                ws = ctx.get("worldspace_fid")
                is_persistent = ctx.get("persistent", False)
                if ws is not None and (ws >> 24) == 0x00:
                    subs = _get_subrecords(data[rs:re], fl)
                    base_fid = 0
                    if b"NAME" in subs and len(subs[b"NAME"]) >= 4:
                        base_fid = struct.unpack_from("<I", subs[b"NAME"], 0)[0]
                    pos = None
                    if b"DATA" in subs and len(subs[b"DATA"]) >= 12:
                        x = struct.unpack_from("<f", subs[b"DATA"], 0)[0]
                        y = struct.unpack_from("<f", subs[b"DATA"], 4)[0]
                        z = struct.unpack_from("<f", subs[b"DATA"], 8)[0]
                        pos = (x, y, z)
                    edid = subs.get(b"EDID", b"").rstrip(b"\x00").decode("utf-8", errors="replace")
                    results.setdefault("vanilla_ws_refrs", []).append({
                        "fid": fid, "base": base_fid, "ws": ws,
                        "persistent": is_persistent, "pos": pos,
                        "edid": edid, "cell_fid": ctx.get("cell_fid", 0),
                    })

            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.scan_vanilla_refs <esm_file>")
        sys.exit(1)

    path = sys.argv[1]
    with open(path, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs

    results = {}
    scan(data, start, len(data), {}, results)

    # 输出 Worldspace 列表
    print("=== Worldspace 列表 ===")
    for fid, info in sorted(results.get("worldspaces", {}).items()):
        tag = "原版" if info["is_vanilla"] else "MOD"
        print(f"  {fid:08X}  [{tag}]  EDID={info['edid']}  FULL={info['full']}")

    # 输出原版记录覆盖
    overrides = results.get("vanilla_overrides", [])
    print(f"\n=== 覆盖原版记录: {len(overrides)} 条 ===")
    type_counts = {}
    for o in overrides:
        type_counts[o["type"]] = type_counts.get(o["type"], 0) + 1
    for t, c in sorted(type_counts.items(), key=lambda x: -x[1]):
        print(f"  {t}: {c} 条")

    # 输出在原版 Worldspace 中的 REFR
    vanilla_refrs = results.get("vanilla_ws_refrs", [])
    print(f"\n=== 在原版 Worldspace 中放置的 REFR: {len(vanilla_refrs)} 条 ===")
    for r in vanilla_refrs[:50]:
        p_tag = "P" if r["persistent"] else "T"
        pos_str = f"({r['pos'][0]:.1f}, {r['pos'][1]:.1f}, {r['pos'][2]:.1f})" if r["pos"] else "(无坐标)"
        print(f"  {r['fid']:08X}  base={r['base']:08X}  WS={r['ws']:08X}  "
              f"Cell={r['cell_fid']:08X}  [{p_tag}]  {pos_str}  {r['edid']}")
    if len(vanilla_refrs) > 50:
        print(f"  ... 还有 {len(vanilla_refrs) - 50} 条")


if __name__ == "__main__":
    main()
