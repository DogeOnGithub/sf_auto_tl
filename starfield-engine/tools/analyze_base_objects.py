"""分析 Persistent Cell 中 REFR 引用的 Base Object 分布。

按 base FormID 分组，统计数量，帮助识别哪些是场景结构件、哪些是杂物。

用法: python -m tools.analyze_base_objects <esm_file>
"""

import struct
import sys
import zlib
from collections import defaultdict

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
INITIALLY_DISABLED_FLAG = 0x00000800
SUBRECORD_HEADER_SIZE = 6

TARGET_CELLS = {
    0x01009811, 0x01006B2C, 0x01007E32, 0x010068CE, 0x01007C0C,
}

CELL_NAMES = {
    0x01009811: "COCOPOI08Small",
    0x01006B2C: "COCOPOI10",
    0x01007E32: "COCOPOI12",
    0x010068CE: "COCOPOI9",
    0x01007C0C: "COCOPOI11",
}


def _decompress(data, flags):
    if not (flags & COMPRESSED_FLAG):
        return data
    if len(data) < 4:
        return b""
    try:
        return zlib.decompress(data[4:], bufsize=struct.unpack_from("<I", data, 0)[0])
    except zlib.error:
        return b""


def _parse_refr(data, flags):
    """解析 REFR 的子记录，提取关键信息。"""
    rec = _decompress(data, flags)
    info = {"name_fid": 0, "edid": "", "sub_types": set(), "has_data": False,
            "pos": None}
    offset = 0
    xxxx_size = None
    while offset + 6 <= len(rec):
        st = rec[offset:offset + 4]
        ss = struct.unpack_from("<H", rec, offset + 4)[0]
        offset += 6
        if st == b"XXXX":
            if ss == 4 and offset + 4 <= len(rec):
                xxxx_size = struct.unpack_from("<I", rec, offset)[0]
            offset += ss
            continue
        if xxxx_size is not None:
            ss = xxxx_size
            xxxx_size = None
        if offset + ss > len(rec):
            break
        info["sub_types"].add(st)
        if st == b"NAME" and ss >= 4:
            info["name_fid"] = struct.unpack_from("<I", rec, offset)[0]
        elif st == b"EDID" and ss > 0:
            info["edid"] = rec[offset:offset + ss].rstrip(b"\x00").decode("utf-8", errors="replace")
        elif st == b"DATA" and ss >= 12:
            x = struct.unpack_from("<f", rec, offset)[0]
            y = struct.unpack_from("<f", rec, offset + 4)[0]
            z = struct.unpack_from("<f", rec, offset + 8)[0]
            info["pos"] = (x, y, z)
            info["has_data"] = True
        offset += ss
    return info


def scan(data, offset, end, ctx, results):
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

            cell_fid = ctx.get("cell_fid", 0)
            is_p = ctx.get("persistent", False)

            if rt == b"REFR" and is_p and cell_fid in TARGET_CELLS:
                info = _parse_refr(data[rs:re], fl)
                info["fid"] = fid
                info["flags"] = fl
                info["cell"] = cell_fid
                results.append(info)

            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.analyze_base_objects <esm_file>")
        sys.exit(1)

    with open(sys.argv[1], "rb") as f:
        data = f.read()

    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs

    results = []
    scan(data, start, len(data), {}, results)

    print(f"目标 Cell 中的 Persistent REFR: {len(results)} 条\n")

    # 按 base FormID 分组
    by_base = defaultdict(list)
    for r in results:
        by_base[r["name_fid"]].append(r)

    # 按数量排序
    sorted_bases = sorted(by_base.items(), key=lambda x: -len(x[1]))

    # 统计子记录丰富度
    print(f"{'Base FID':>10}  {'数量':>5}  {'来源':>4}  {'子记录种类':>6}  {'示例子记录'}")
    print("-" * 100)

    for base_fid, refs in sorted_bases[:80]:
        origin = "MOD" if (base_fid >> 24) == 0x01 else "原版"
        # 统计这组 REFR 的子记录类型
        all_subs = set()
        for r in refs:
            all_subs.update(r["sub_types"])
        # 去掉 NAME 和 DATA 这些基础的
        special_subs = all_subs - {b"NAME", b"DATA", b"XSCL", b"XOWN"}
        sub_str = " ".join(sorted(s.decode("ascii", errors="replace") for s in special_subs))
        if not sub_str:
            sub_str = "(仅基础子记录)"

        # 看看分布在哪些 Cell
        cells = set(r["cell"] for r in refs)
        cell_str = ",".join(CELL_NAMES.get(c, f"{c:08X}") for c in cells)

        print(f"  {base_fid:08X}  {len(refs):>5}  {origin:>4}  {len(all_subs):>6}  "
              f"{sub_str[:50]}  [{cell_str}]")

    # 汇总
    vanilla_count = sum(len(refs) for fid, refs in by_base.items() if (fid >> 24) != 0x01)
    mod_count = sum(len(refs) for fid, refs in by_base.items() if (fid >> 24) == 0x01)
    print(f"\n原版 base object 的 REFR: {vanilla_count} 条")
    print(f"MOD 自定义 base object 的 REFR: {mod_count} 条")

    # 子记录分析
    only_basic = 0  # 只有 NAME+DATA 等基础子记录
    has_special = 0
    for r in results:
        special = r["sub_types"] - {b"NAME", b"DATA", b"XSCL", b"XOWN"}
        if special:
            has_special += 1
        else:
            only_basic += 1
    print(f"\n只有基础子记录的 REFR: {only_basic} 条")
    print(f"有特殊子记录的 REFR: {has_special} 条")


if __name__ == "__main__":
    main()
