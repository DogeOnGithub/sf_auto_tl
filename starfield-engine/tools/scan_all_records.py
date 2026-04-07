"""扫描 ESM 文件中所有记录类型及其数量，区分原版覆盖和 MOD 新增。

用法: python -m tools.scan_all_records <esm_file>
"""

import struct
import sys
from collections import defaultdict

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24


def scan(data: bytes, offset: int, end: int, results: dict):
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
            scan(data, offset + GRUP_HEADER_SIZE, ge, results)
            offset = ge
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            ds = struct.unpack_from("<I", data, offset + 4)[0]
            fid = struct.unpack_from("<I", data, offset + 12)[0]
            re = min(offset + RECORD_HEADER_SIZE + ds, end)
            rtype = rt.decode("ascii", errors="replace")
            is_vanilla = (fid >> 24) == 0x00 and fid != 0
            key = (rtype, "vanilla" if is_vanilla else "mod")
            results[key] = results.get(key, 0) + 1
            if is_vanilla:
                results.setdefault("vanilla_details", []).append({
                    "type": rtype, "fid": fid, "size": ds,
                })
            offset = re


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.scan_all_records <esm_file>")
        sys.exit(1)

    with open(sys.argv[1], "rb") as f:
        data = f.read()

    hs = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + hs

    results = {}
    scan(data, start, len(data), results)

    # 按类型汇总
    type_summary = defaultdict(lambda: {"mod": 0, "vanilla": 0})
    for key, val in results.items():
        if not isinstance(key, tuple):
            continue
        rtype, origin = key
        if origin in ("mod", "vanilla"):
            type_summary[rtype][origin] = val

    print(f"{'类型':>6}  {'MOD新增':>8}  {'原版覆盖':>8}  {'合计':>8}")
    print("-" * 40)
    for rtype in sorted(type_summary.keys()):
        s = type_summary[rtype]
        total = s["mod"] + s["vanilla"]
        print(f"{rtype:>6}  {s['mod']:>8}  {s['vanilla']:>8}  {total:>8}")

    # 列出所有原版覆盖的详情
    vanilla = results.get("vanilla_details", [])
    if vanilla:
        print(f"\n=== 原版覆盖详情 ({len(vanilla)} 条) ===")
        for v in sorted(vanilla, key=lambda x: x["type"]):
            print(f"  {v['type']}  {v['fid']:08X}  size={v['size']}")


if __name__ == "__main__":
    main()
