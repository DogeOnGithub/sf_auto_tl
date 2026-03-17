"""统计 ESM 文件中所有 Cell 的类型分布。

用法: python -m tools.count_cells <esm_file_path>
"""

import struct
import sys
import zlib
from collections import defaultdict

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _get_cell_edid(data: bytes, flags: int) -> str:
    rec_data = data
    if flags & COMPRESSED_FLAG and len(rec_data) >= 4:
        try:
            rec_data = zlib.decompress(rec_data[4:], bufsize=struct.unpack_from("<I", rec_data, 0)[0])
        except zlib.error:
            return ""
    offset = 0
    while offset + SUBRECORD_HEADER_SIZE <= len(rec_data):
        sub_type = rec_data[offset:offset + 4]
        sub_size = struct.unpack_from("<H", rec_data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE
        if offset + sub_size > len(rec_data):
            break
        if sub_type == b"EDID" and sub_size > 0:
            return rec_data[offset:offset + sub_size].rstrip(b"\x00").decode("utf-8", errors="replace")
        offset += sub_size
    return ""


def scan(data, offset, end, ctx, cells, worldspaces):
    while offset < end:
        if offset + 4 > end:
            break
        rec_type = data[offset:offset + 4]

        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            group_type = struct.unpack_from("<I", data, offset + 12)[0]
            group_label = data[offset + 8:offset + 12]
            if group_size < GRUP_HEADER_SIZE:
                break
            group_end = min(offset + group_size, end)

            new_ctx = dict(ctx)
            if group_type == 0:
                new_ctx["top"] = group_label.decode("ascii", errors="replace").strip("\x00")
            elif group_type == 1:
                new_ctx["wrld_fid"] = struct.unpack_from("<I", group_label, 0)[0]

            scan(data, offset + GRUP_HEADER_SIZE, group_end, new_ctx, cells, worldspaces)
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
                edid = _get_cell_edid(data[rec_start:rec_end], flags)
                worldspaces[form_id] = edid

            if rec_type == b"CELL":
                is_interior = ctx.get("top") == "CELL"
                wrld_fid = ctx.get("wrld_fid", 0)
                edid = _get_cell_edid(data[rec_start:rec_end], flags)
                cells.append({
                    "form_id": form_id,
                    "is_interior": is_interior,
                    "wrld_fid": wrld_fid,
                    "edid": edid,
                })

            offset = rec_end


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.count_cells <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    with open(file_path, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    cells = []
    worldspaces = {}
    scan(data, start, len(data), {}, cells, worldspaces)

    interior = [c for c in cells if c["is_interior"]]
    exterior = [c for c in cells if not c["is_interior"]]

    print(f"共 {len(cells)} 个 Cell: {len(interior)} Interior + {len(exterior)} Exterior")
    print(f"共 {len(worldspaces)} 个 Worldspace\n")

    # 按 Worldspace 分组 Exterior Cell
    wrld_groups = defaultdict(list)
    for c in exterior:
        wrld_groups[c["wrld_fid"]].append(c)

    print("=" * 80)
    print("  Worldspace 分布")
    print("=" * 80)
    for wrld_fid, wrld_cells in sorted(wrld_groups.items(), key=lambda x: -len(x[1])):
        wrld_name = worldspaces.get(wrld_fid, "(未知)")
        print(f"  WRLD {wrld_fid:08X} ({wrld_name}): {len(wrld_cells)} 个 Exterior Cell")

    print(f"\n{'=' * 80}")
    print("  Interior Cell 列表")
    print("=" * 80)
    for c in interior:
        label = c["edid"] or "(无名称)"
        print(f"  {c['form_id']:08X}  {label}")


if __name__ == "__main__":
    main()
