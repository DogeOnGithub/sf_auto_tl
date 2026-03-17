"""深入分析 Persistent REFR 的归属，区分 Interior Cell 和 Worldspace Exterior Cell。

用法: python -m tools.analyze_persistent <esm_file_path>
"""

import struct
import sys
import zlib
from collections import defaultdict
from dataclasses import dataclass, field

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _get_cell_info(data: bytes) -> dict:
    """从 CELL 记录中提取信息。"""
    result = {"edid": "", "full": "", "flags": 0}
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
        elif sub_type == b"DATA" and sub_size >= 2:
            result["flags"] = struct.unpack_from("<H", sub_data, 0)[0]
        offset += sub_size
    return result


def _get_refr_base(data: bytes, flags: int) -> int:
    """从 REFR 记录中提取 NAME (base FormID)。"""
    rec_data = data
    if flags & COMPRESSED_FLAG:
        if len(rec_data) < 4:
            return 0
        try:
            rec_data = zlib.decompress(rec_data[4:], bufsize=struct.unpack_from("<I", rec_data, 0)[0])
        except zlib.error:
            return 0
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
        if sub_type == b"NAME" and sub_size >= 4:
            return struct.unpack_from("<I", rec_data, offset)[0]
        offset += sub_size
    return 0


@dataclass
class CellAnalysis:
    """一个 Cell 的分析结果。"""
    form_id: int = 0
    edid: str = ""
    full: str = ""
    is_interior: bool = False  # 在 CELL 顶层 GRUP 下 = Interior
    worldspace_label: str = ""  # 如果在 WRLD 下，记录 Worldspace 信息
    persistent_count: int = 0
    temporary_count: int = 0
    persistent_base_fids: list = field(default_factory=list)


def scan_structure(data: bytes, offset: int, end: int, context: dict, cells: dict):
    """递归扫描，追踪 Cell 和 REFR 的关系。"""
    while offset < end:
        if offset + 4 > end:
            break
        rec_type = data[offset:offset + 4]

        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            group_type = struct.unpack_from("<I", data, offset + 12)[0]
            group_label_raw = data[offset + 8:offset + 12]

            if group_size < GRUP_HEADER_SIZE:
                break
            group_end = min(offset + group_size, end)

            new_ctx = dict(context)

            if group_type == 0:
                # Top-Level GRUP
                label = group_label_raw.decode("ascii", errors="replace").strip("\x00")
                new_ctx["top_level"] = label
            elif group_type == 1:
                # World Children
                fid = struct.unpack_from("<I", group_label_raw, 0)[0]
                new_ctx["worldspace_fid"] = fid
            elif group_type == 8:
                # Cell Persistent Children
                fid = struct.unpack_from("<I", group_label_raw, 0)[0]
                new_ctx["cell_fid"] = fid
                new_ctx["persistent"] = True
            elif group_type == 9:
                # Cell Temporary Children
                fid = struct.unpack_from("<I", group_label_raw, 0)[0]
                new_ctx["cell_fid"] = fid
                new_ctx["persistent"] = False
            elif group_type == 6:
                # Cell Children
                fid = struct.unpack_from("<I", group_label_raw, 0)[0]
                new_ctx["cell_fid"] = fid

            scan_structure(data, offset + GRUP_HEADER_SIZE, group_end, new_ctx, cells)
            offset = group_end

        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = min(rec_start + data_size, end)

            if rec_type == b"CELL":
                rec_data = data[rec_start:rec_end]
                if flags & COMPRESSED_FLAG and len(rec_data) >= 4:
                    try:
                        rec_data = zlib.decompress(rec_data[4:], bufsize=struct.unpack_from("<I", rec_data, 0)[0])
                    except zlib.error:
                        rec_data = b""
                info = _get_cell_info(rec_data)
                cell = cells.setdefault(form_id, CellAnalysis(form_id=form_id))
                cell.edid = info["edid"]
                cell.full = info["full"]
                cell.is_interior = context.get("top_level") == "CELL"
                if "worldspace_fid" in context:
                    cell.worldspace_label = f"WRLD:{context['worldspace_fid']:08X}"

            elif rec_type == b"REFR":
                cell_fid = context.get("cell_fid", 0)
                is_persistent = context.get("persistent", False)
                if cell_fid:
                    cell = cells.setdefault(cell_fid, CellAnalysis(form_id=cell_fid))
                    if is_persistent:
                        cell.persistent_count += 1
                        base_fid = _get_refr_base(data[rec_start:rec_end], flags)
                        if base_fid:
                            cell.persistent_base_fids.append(base_fid)
                    else:
                        cell.temporary_count += 1

            offset = rec_end


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.analyze_persistent <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    print(f"深入分析 Persistent 结构: {file_path}\n")

    with open(file_path, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    cells = {}
    scan_structure(data, start, len(data), {}, cells)

    # 找出有 Persistent REFR 的 Cell
    persistent_cells = {fid: c for fid, c in cells.items() if c.persistent_count > 0}

    print(f"共 {len(cells)} 个 Cell，其中 {len(persistent_cells)} 个含有 Persistent REFR\n")

    # 按 Persistent 数量排序
    sorted_cells = sorted(persistent_cells.values(), key=lambda c: c.persistent_count, reverse=True)

    print("=" * 100)
    print(f"  {'Cell FormID':>12}  {'类型':>10}  {'归属':>20}  {'P':>6}  {'T':>6}  {'Editor ID / 名称'}")
    print("=" * 100)

    for c in sorted_cells:
        cell_type = "Interior" if c.is_interior else "Exterior"
        belong = c.worldspace_label if c.worldspace_label else "CELL(Interior)"
        label = c.edid or c.full or "(无名称)"
        print(f"  {c.form_id:08X}      {cell_type:>10}  {belong:>20}  {c.persistent_count:>6}  {c.temporary_count:>6}  {label}")

    # 重点分析：全 Persistent 的 Cell（T=0 的）
    all_persistent = [c for c in sorted_cells if c.temporary_count == 0 and c.persistent_count > 100]
    if all_persistent:
        print(f"\n{'=' * 100}")
        print("  ⚠️  以下 Cell 的所有 REFR 都在 Persistent 中（无 Temporary），高度可疑:")
        print("=" * 100)
        for c in all_persistent:
            cell_type = "Interior" if c.is_interior else "Exterior"
            belong = c.worldspace_label if c.worldspace_label else "CELL(Interior)"
            label = c.edid or c.full or "(无名称)"
            print(f"\n  Cell {c.form_id:08X} ({cell_type}, {belong}) - {label}")
            print(f"  Persistent REFR: {c.persistent_count} 条, Temporary: 0 条")
            print(f"  这个 Cell 的所有物品都会被引擎持久加载，不会随距离卸载")

            # 统计这个 Cell 里的 Base FormID
            base_counts = defaultdict(int)
            for fid in c.persistent_base_fids:
                base_counts[fid] += 1
            top_bases = sorted(base_counts.items(), key=lambda x: -x[1])[:10]
            print(f"  Top Base Objects:")
            for base_fid, count in top_bases:
                print(f"    {base_fid:08X}  x{count}")

    # Worldspace Persistent Cell 分析
    wrld_persistent = [c for c in sorted_cells if not c.is_interior and c.persistent_count > 50]
    if wrld_persistent:
        print(f"\n{'=' * 100}")
        print("  Worldspace 下的 Persistent Cell（大世界 POI 相关）:")
        print("=" * 100)
        for c in wrld_persistent:
            label = c.edid or c.full or "(无名称)"
            print(f"\n  Cell {c.form_id:08X} ({c.worldspace_label}) - {label}")
            print(f"  Persistent: {c.persistent_count}, Temporary: {c.temporary_count}")
            if c.temporary_count == 0:
                print(f"  ⚠️  全部 Persistent! 这些物品会在大世界中始终加载")


if __name__ == "__main__":
    main()
