"""分析 ESM 文件中 REFR 记录的放置结构，诊断杂物凭空出现的根本原因。

检查项：
1. REFR 所在的 GRUP 层级（Worldspace / Cell / Persistent vs Temporary）
2. REFR 的坐标数据是否异常
3. 是否有 REFR 被放在 Persistent Cell 中（不该持久化的物品）
4. 是否有 REFR 坐标为 0,0,0（未正确设置位置）

用法: python -m tools.analyze_refr_placement <esm_file_path>
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

# GRUP type 含义
GRUP_TYPE_NAMES = {
    0: "Top-Level",
    1: "World Children",
    2: "Interior Cell Block",
    3: "Interior Cell Sub-Block",
    4: "Exterior Cell Block",
    5: "Exterior Cell Sub-Block",
    6: "Cell Children",
    7: "Topic Children",
    8: "Cell Persistent Children",
    9: "Cell Temporary Children",
}


@dataclass
class RefrInfo:
    """一条 REFR 记录的诊断信息。"""
    form_id: int = 0
    base_formid: int = 0
    edid: str = ""
    position: tuple = (0.0, 0.0, 0.0)
    grup_path: list = field(default_factory=list)
    is_persistent: bool = False
    is_temporary: bool = False
    cell_formid: int = 0


def _parse_refr_data(data: bytes) -> dict:
    """解析 REFR 子记录，提取 NAME、EDID、DATA（坐标）。"""
    result = {"base_formid": 0, "edid": "", "position": (0.0, 0.0, 0.0)}
    offset = 0
    xxxx_size = None

    while offset + SUBRECORD_HEADER_SIZE <= len(data):
        sub_type = data[offset:offset + 4]
        sub_size = struct.unpack_from("<H", data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE

        if sub_type == b"XXXX":
            if sub_size == 4 and offset + 4 <= len(data):
                xxxx_size = struct.unpack_from("<I", data, offset)[0]
            offset += sub_size
            continue

        if xxxx_size is not None:
            sub_size = xxxx_size
            xxxx_size = None

        if offset + sub_size > len(data):
            break

        sub_data = data[offset:offset + sub_size]

        if sub_type == b"NAME" and sub_size >= 4:
            result["base_formid"] = struct.unpack_from("<I", sub_data, 0)[0]
        elif sub_type == b"EDID" and sub_size > 0:
            result["edid"] = sub_data.rstrip(b"\x00").decode("utf-8", errors="replace")
        elif sub_type == b"DATA" and sub_size >= 12:
            x = struct.unpack_from("<f", sub_data, 0)[0]
            y = struct.unpack_from("<f", sub_data, 4)[0]
            z = struct.unpack_from("<f", sub_data, 8)[0]
            result["position"] = (x, y, z)

        offset += sub_size

    return result


def analyze_records(data: bytes, offset: int, end: int, grup_stack: list, refr_list: list):
    """递归分析记录结构，追踪 GRUP 层级。"""
    while offset < end:
        if offset + 4 > end:
            break
        rec_type = data[offset:offset + 4]

        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            group_label = data[offset + 8:offset + 12]
            group_type = struct.unpack_from("<I", data, offset + 12)[0]

            if group_size < GRUP_HEADER_SIZE:
                break
            group_end = min(offset + group_size, end)

            # 构建 GRUP 描述
            type_name = GRUP_TYPE_NAMES.get(group_type, f"Unknown({group_type})")
            if group_type == 0:
                # Top-level: label 是记录类型
                label_str = group_label.decode("ascii", errors="replace").strip("\x00")
                grup_desc = f"{type_name}({label_str})"
            elif group_type in (2, 3, 4, 5):
                # Block/Sub-Block: label 是数字
                label_val = struct.unpack_from("<I", data, offset + 8)[0]
                grup_desc = f"{type_name}({label_val})"
            elif group_type in (1, 6, 8, 9):
                # Children: label 是 FormID
                label_fid = struct.unpack_from("<I", data, offset + 8)[0]
                grup_desc = f"{type_name}({label_fid:08X})"
            else:
                grup_desc = f"{type_name}"

            grup_stack.append((group_type, grup_desc, group_label))
            analyze_records(data, offset + GRUP_HEADER_SIZE, group_end, grup_stack, refr_list)
            grup_stack.pop()

            offset = group_end
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = min(rec_start + data_size, end)

            if rec_type == b"REFR":
                rec_data = data[rec_start:rec_end]
                if flags & COMPRESSED_FLAG:
                    if len(rec_data) >= 4:
                        decomp_size = struct.unpack_from("<I", rec_data, 0)[0]
                        try:
                            rec_data = zlib.decompress(rec_data[4:], bufsize=decomp_size)
                        except zlib.error:
                            offset = rec_end
                            continue

                info = _parse_refr_data(rec_data)
                refr = RefrInfo(
                    form_id=form_id,
                    base_formid=info["base_formid"],
                    edid=info["edid"],
                    position=info["position"],
                    grup_path=[desc for _, desc, _ in grup_stack],
                )

                # 判断是否在 Persistent 或 Temporary GRUP 中
                for gt, _, _ in grup_stack:
                    if gt == 8:
                        refr.is_persistent = True
                    elif gt == 9:
                        refr.is_temporary = True

                # 找到所属 Cell 的 FormID
                for gt, _, label in reversed(grup_stack):
                    if gt in (6, 8, 9):
                        refr.cell_formid = struct.unpack_from("<I", label, 0)[0]
                        break

                refr_list.append(refr)

            offset = rec_end


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.analyze_refr_placement <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    print(f"分析 REFR 放置结构: {file_path}")

    with open(file_path, "rb") as f:
        data = f.read()

    print(f"文件大小: {len(data):,} bytes")

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    refr_list = []
    analyze_records(data, start, len(data), [], refr_list)

    print(f"\n共 {len(refr_list)} 条 REFR 记录\n")

    # === 统计分析 ===

    # 1. Persistent vs Temporary
    persistent_count = sum(1 for r in refr_list if r.is_persistent)
    temporary_count = sum(1 for r in refr_list if r.is_temporary)
    neither_count = sum(1 for r in refr_list if not r.is_persistent and not r.is_temporary)

    print("=" * 80)
    print("  1. Persistent vs Temporary 分布")
    print("=" * 80)
    print(f"  Persistent (持久化): {persistent_count}")
    print(f"  Temporary  (临时):   {temporary_count}")
    print(f"  Neither    (未归类): {neither_count}")

    if persistent_count > 0:
        print(f"\n  ⚠️  有 {persistent_count} 条 REFR 在 Persistent GRUP 中")
        print("  Persistent 记录会在所有场景加载时始终存在，这可能是杂物到处出现的原因")

    # 2. 按 Cell 分组
    cell_groups = defaultdict(list)
    for r in refr_list:
        cell_groups[r.cell_formid].append(r)

    print(f"\n{'=' * 80}")
    print("  2. Cell 分布")
    print("=" * 80)
    print(f"  共 {len(cell_groups)} 个 Cell\n")

    sorted_cells = sorted(cell_groups.items(), key=lambda x: len(x[1]), reverse=True)
    for cell_fid, refs in sorted_cells[:20]:
        p_count = sum(1 for r in refs if r.is_persistent)
        t_count = sum(1 for r in refs if r.is_temporary)
        print(f"  Cell {cell_fid:08X}: {len(refs):>5} 条 REFR (P={p_count} T={t_count})")

    if len(sorted_cells) > 20:
        print(f"  ... 还有 {len(sorted_cells) - 20} 个 Cell")

    # 3. 坐标异常检测
    zero_pos = [r for r in refr_list if r.position == (0.0, 0.0, 0.0)]
    extreme_pos = [r for r in refr_list if any(abs(v) > 1e8 for v in r.position)]

    print(f"\n{'=' * 80}")
    print("  3. 坐标异常检测")
    print("=" * 80)
    print(f"  坐标为 (0,0,0): {len(zero_pos)} 条")
    print(f"  坐标极端值:     {len(extreme_pos)} 条")

    if zero_pos:
        print(f"\n  坐标 (0,0,0) 的 REFR 示例:")
        for r in zero_pos[:10]:
            path_str = " > ".join(r.grup_path[-3:]) if len(r.grup_path) > 3 else " > ".join(r.grup_path)
            print(f"    {r.form_id:08X} base={r.base_formid:08X} [{path_str}]")

    # 4. GRUP 路径分析 - 找出异常的放置位置
    print(f"\n{'=' * 80}")
    print("  4. GRUP 层级结构分析")
    print("=" * 80)

    # 统计 REFR 所在的顶层 GRUP 类型
    top_grup_counts = defaultdict(int)
    for r in refr_list:
        if r.grup_path:
            top_grup_counts[r.grup_path[0]] += 1

    print("  顶层 GRUP 分布:")
    for grup, count in sorted(top_grup_counts.items(), key=lambda x: -x[1]):
        print(f"    {grup}: {count} 条 REFR")

    # 5. 诊断结论
    print(f"\n{'=' * 80}")
    print("  5. 诊断结论")
    print("=" * 80)

    issues = []
    if persistent_count > 100:
        issues.append(
            f"大量 REFR ({persistent_count}) 在 Persistent GRUP 中。\n"
            "    Persistent 记录会被引擎持久加载，不随 Cell 卸载而消失。\n"
            "    如果这些是杂物（盘子、垃圾桶等），它们会在所有场景中出现。\n"
            "    修复方案: 将不需要持久化的 REFR 移到 Temporary GRUP，或直接删除。"
        )
    if neither_count > 0:
        issues.append(
            f"有 {neither_count} 条 REFR 未归属于 Persistent 或 Temporary GRUP。\n"
            "    这些记录可能放置在错误的 GRUP 层级下，引擎行为不可预测。"
        )
    if zero_pos:
        issues.append(
            f"有 {len(zero_pos)} 条 REFR 坐标为 (0,0,0)。\n"
            "    这通常意味着位置数据未正确设置，物品会出现在世界原点。"
        )
    if len(cell_groups) == 1:
        issues.append(
            "所有 REFR 都在同一个 Cell 中。\n"
            "    如果这个 Cell 是 Persistent Cell，所有物品都会全局加载。"
        )

    if issues:
        for i, issue in enumerate(issues, 1):
            print(f"\n  问题 {i}: {issue}")
    else:
        print("\n  未发现明显的结构性问题。")

    # 6. 如果 Persistent 是主要问题，列出可以移除的 Persistent REFR
    if persistent_count > 100:
        print(f"\n{'=' * 80}")
        print("  6. Persistent REFR 的 Base FormID 统计 (疑似不需要持久化)")
        print("=" * 80)

        persistent_refs = [r for r in refr_list if r.is_persistent]
        base_counts = defaultdict(int)
        for r in persistent_refs:
            base_counts[r.base_formid] += 1

        sorted_bases = sorted(base_counts.items(), key=lambda x: -x[1])
        print(f"\n  共 {len(sorted_bases)} 种 Base Object 在 Persistent GRUP 中\n")
        for base_fid, count in sorted_bases[:30]:
            sample = next(r for r in persistent_refs if r.base_formid == base_fid)
            label = sample.edid or "(无名称)"
            print(f"    {base_fid:08X}  x{count:>3}  {label}")
        if len(sorted_bases) > 30:
            print(f"    ... 还有 {len(sorted_bases) - 30} 种")


if __name__ == "__main__":
    main()
