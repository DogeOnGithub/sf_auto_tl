"""统计 ESM/ESP 文件中的 REFR（引用）记录数量。

用法:
  python -m tools.count_refr <esm_file>

示例:
  python -m tools.count_refr Starfield.esm
"""

import struct
import sys

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000


def count_refr(path: str) -> dict[str, int]:
    """统计 ESM 文件中各类型记录数量，重点关注 REFR。

    返回 {record_type: count} 的字典。
    """
    counts: dict[str, int] = {}
    refr_persistent = 0
    refr_temporary = 0

    with open(path, "rb") as f:
        data = f.read()

    size = len(data)
    # 跳过 TES4 头记录
    if data[:4] != b"TES4":
        raise ValueError(f"不是有效的 ESM/ESP 文件，首记录类型为: {data[:4]}")

    tes4_data_size = struct.unpack_from("<I", data, 4)[0]
    offset = RECORD_HEADER_SIZE + tes4_data_size

    def scan(start: int, end: int, in_persistent_group: bool = False) -> None:
        nonlocal refr_persistent, refr_temporary
        pos = start
        while pos < end:
            if pos + 4 > end:
                break
            rec_type = data[pos:pos + 4]

            if rec_type == b"GRUP":
                if pos + GRUP_HEADER_SIZE > end:
                    break
                grup_size = struct.unpack_from("<I", data, pos + 4)[0]
                grup_type = struct.unpack_from("<I", data, pos + 12)[0]
                # grup_type 8 = persistent children, 9 = temporary children
                child_persistent = in_persistent_group or (grup_type == 8)
                scan(pos + GRUP_HEADER_SIZE, pos + grup_size, child_persistent)
                pos += grup_size
            else:
                if pos + RECORD_HEADER_SIZE > end:
                    break
                data_size = struct.unpack_from("<I", data, pos + 4)[0]
                type_str = rec_type.decode("ascii", errors="replace")
                counts[type_str] = counts.get(type_str, 0) + 1

                if rec_type == b"REFR":
                    if in_persistent_group:
                        refr_persistent += 1
                    else:
                        refr_temporary += 1

                pos += RECORD_HEADER_SIZE + data_size

    scan(offset, size)
    return counts, refr_persistent, refr_temporary


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python -m tools.count_refr <esm_file>")
        sys.exit(1)

    path = sys.argv[1]
    print(f"正在分析: {path}")
    counts, persistent, temporary = count_refr(path)

    total_refr = counts.get("REFR", 0)
    total_records = sum(counts.values())

    print(f"\n总记录数: {total_records:,}")
    print(f"REFR 引用数: {total_refr:,}")
    print(f"  - 持久引用 (persistent): {persistent:,}")
    print(f"  - 临时引用 (temporary): {temporary:,}")

    print(f"\n各类型记录 TOP 20:")
    for typ, cnt in sorted(counts.items(), key=lambda x: -x[1])[:20]:
        pct = cnt / total_records * 100
        print(f"  {typ:8s} {cnt:>10,}  ({pct:.1f}%)")
