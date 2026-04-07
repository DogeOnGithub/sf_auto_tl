"""修复 ESM 文件中错误放置在 Persistent GRUP 的 REFR 记录。

将 Worldspace Persistent Cell 中不需要持久化的 REFR 移到 Temporary Cell Children 中。

修复策略：
- 只处理指定 Worldspace 的 Persistent Cell
- 通过扫描 REFR 内部子记录精确判断是否需要持久化
- 保留功能性 REFR（有脚本、Enable Parent、Linked Ref、EDID 等）
- 将纯装饰物 REFR 从 Persistent Children 移到 Temporary Children

用法: python -m tools.fix_persistent <input_esm> <output_esm>
"""

import struct
import sys
import zlib
from pathlib import Path

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
INITIALLY_DISABLED_FLAG = 0x00000800
SUBRECORD_HEADER_SIZE = 6

# 需要修复的 Worldspace Persistent Cell FormID -> Worldspace EDID
TARGET_CELLS = {
    0x01009811: "COCOPOI08Small",
    0x01006B2C: "COCOPOI10",
    0x01007E32: "COCOPOI12",
    0x010068CE: "COCOPOI9",
    0x01007C0C: "COCOPOI11",
}


def _decompress_record(data: bytes, flags: int) -> bytes:
    """解压缩记录数据（如果有压缩标志）。"""
    if not (flags & COMPRESSED_FLAG):
        return data
    if len(data) < 4:
        return b""
    try:
        return zlib.decompress(data[4:], bufsize=struct.unpack_from("<I", data, 0)[0])
    except zlib.error:
        return b""


def _scan_subrecord_types(data: bytes, flags: int) -> dict:
    """扫描 REFR 记录的所有子记录，返回子记录类型集合和 NAME 值。

    返回 dict:
      - "types": set of subrecord type bytes (如 {b"NAME", b"VMAD", b"XESP", ...})
      - "name_fid": NAME 子记录的 FormID 值（base object 引用）
      - "edid": EDID 子记录的文本值
    """
    rec_data = _decompress_record(data, flags)
    if not rec_data:
        return {"types": set(), "name_fid": 0, "edid": ""}

    result = {"types": set(), "name_fid": 0, "edid": ""}
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
        result["types"].add(sub_type)
        if sub_type == b"NAME" and sub_size >= 4:
            result["name_fid"] = struct.unpack_from("<I", rec_data, offset)[0]
        elif sub_type == b"EDID" and sub_size > 0:
            result["edid"] = rec_data[offset:offset + sub_size].rstrip(b"\x00").decode(
                "utf-8", errors="replace"
            )
        offset += sub_size
    return result


# 功能性子记录类型 — 包含这些子记录的 REFR 需要保留在 Persistent 中
# 分为两类：
# 1. 逻辑功能性：脚本、enable 链、链接引用等
# 2. 渲染功能性：预合并引用组、图层、LOD 等
FUNCTIONAL_SUBRECORDS = {
    # 逻辑功能性
    b"VMAD",  # 脚本数据（Papyrus Virtual Machine Adapter）
    b"XESP",  # Enable State Parent（启用/禁用父物品关联）
    b"XLKR",  # Linked Reference（链接引用，用于巡逻路径等）
    b"XPRD",  # Patrol Data（巡逻数据）
    b"XACT",  # Action Flag（动作标志）
    b"XNDP",  # Navigation Door Portal（导航门传送）
    b"XTEL",  # Teleport Destination（传送目的地）
    b"XTNM",  # Teleport Name（传送名称）
    b"XMBR",  # MultiBound Reference（多边界引用）
    b"XPPA",  # Patrol Point Arrival（巡逻点到达）
    b"XRGD",  # Ragdoll Data（布娃娃数据）
    b"XRDO",  # Radio Data（广播数据）
    # 渲染功能性
    b"XRFG",  # Reference Group（预合并渲染组关联）
    b"XLYR",  # Layer（图层归属）
    b"XLMS",  # LOD/材质系统相关
    b"XGDS",  # 几何数据系统相关
}


def _should_keep_persistent(rec_type: bytes, form_id: int, flags: int,
                            rec_data: bytes) -> tuple[bool, str]:
    """判断一条记录是否应该保留在 Persistent GRUP 中。

    返回 (should_keep, reason) 元组。

    保留条件（任一满足即保留）：
    1. 不是 REFR 类型（ACHR、PGRE 等其他引用类型需要保留）
    2. 有 Initially Disabled 标志（脚本控制的物品）
    3. Base FormID 是 MOD 自身定义的（01xxxxxx）
    4. 有 EDID（Editor ID，MOD 作者有意命名的关键物品）
    5. 有功能性子记录（VMAD/XESP/XLKR 等）
    """
    # 非 REFR 记录保留
    if rec_type != b"REFR":
        return True, "非REFR"

    # Initially Disabled 的保留
    if flags & INITIALLY_DISABLED_FLAG:
        return True, "InitiallyDisabled"

    # 扫描子记录
    sub_info = _scan_subrecord_types(rec_data, flags)

    # MOD 自身定义的 base object 保留
    base_fid = sub_info["name_fid"]
    if base_fid and (base_fid >> 24) == 0x01:
        return True, "MOD自定义Base"

    # 有 EDID 的保留（MOD 作者有意命名的关键物品）
    if sub_info["edid"]:
        return True, f"有EDID({sub_info['edid']})"

    # 有功能性子记录的保留
    found = sub_info["types"] & FUNCTIONAL_SUBRECORDS
    if found:
        names = "/".join(s.decode("ascii") for s in sorted(found))
        return True, f"功能性子记录({names})"

    return False, "纯装饰物"


def _rewrite_with_fix(data: bytes, offset: int, end: int, context: dict,
                      stats: dict) -> bytes:
    """递归重写记录，将目标 Cell 的 Persistent REFR 移到 Temporary。

    核心逻辑：
    1. 遇到目标 Cell 的 Persistent Children GRUP 时，分离出需要移动的 REFR
    2. 遇到同一 Cell 的 Temporary Children GRUP 时，将分离出的 REFR 追加进去
    3. 如果目标 Cell 没有 Temporary Children GRUP，创建一个
    """
    parts: list[bytes] = []

    while offset < end:
        if offset + 4 > end:
            parts.append(data[offset:end])
            break

        rec_type = data[offset:offset + 4]

        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                parts.append(data[offset:end])
                break

            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            group_type = struct.unpack_from("<I", data, offset + 12)[0]
            group_label_raw = data[offset + 8:offset + 12]

            if group_size < GRUP_HEADER_SIZE:
                parts.append(data[offset:end])
                break

            group_end = min(offset + group_size, end)

            if group_type == 6:
                # Cell Children GRUP - 这里面包含 Persistent (type 8) 和 Temporary (type 9)
                cell_fid = struct.unpack_from("<I", group_label_raw, 0)[0]

                if cell_fid in TARGET_CELLS:
                    # 这是目标 Cell，需要特殊处理
                    fixed_data = _fix_cell_children(
                        data, offset + GRUP_HEADER_SIZE, group_end,
                        cell_fid, stats
                    )
                    # 重建 Cell Children GRUP
                    grup_header = bytearray(data[offset:offset + GRUP_HEADER_SIZE])
                    new_size = GRUP_HEADER_SIZE + len(fixed_data)
                    struct.pack_into("<I", grup_header, 4, new_size)
                    parts.append(bytes(grup_header) + fixed_data)
                    offset = group_end
                    continue

            # 非目标 Cell 或非 Cell Children GRUP，递归处理
            grup_header = bytearray(data[offset:offset + GRUP_HEADER_SIZE])
            inner = _rewrite_with_fix(data, offset + GRUP_HEADER_SIZE, group_end, context, stats)
            new_size = GRUP_HEADER_SIZE + len(inner)
            struct.pack_into("<I", grup_header, 4, new_size)
            parts.append(bytes(grup_header) + inner)
            offset = group_end

        else:
            if offset + RECORD_HEADER_SIZE > end:
                parts.append(data[offset:end])
                break

            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = min(rec_start + data_size, end)

            parts.append(data[offset:rec_end])
            offset = rec_end

    return b"".join(parts)


def _fix_cell_children(data: bytes, offset: int, end: int,
                       cell_fid: int, stats: dict) -> bytes:
    """处理一个目标 Cell 的 Children GRUP 内容。

    扫描 Persistent Children 和 Temporary Children，将不需要持久化的 REFR
    从 Persistent 移到 Temporary。
    """
    persistent_grup_header = None
    persistent_keep = []      # 保留在 Persistent 中的记录
    persistent_move = []      # 需要移到 Temporary 的记录
    temporary_grup_header = None
    temporary_records = []    # 原有的 Temporary 记录
    other_parts = []          # 其他内容（不应该有，但以防万一）

    pos = offset
    while pos < end:
        if pos + 4 > end:
            other_parts.append(data[pos:end])
            break

        rt = data[pos:pos + 4]
        if rt != b"GRUP":
            # Cell Children 下应该只有 GRUP，但保险起见处理
            if pos + RECORD_HEADER_SIZE > end:
                other_parts.append(data[pos:end])
                break
            ds = struct.unpack_from("<I", data, pos + 4)[0]
            re = min(pos + RECORD_HEADER_SIZE + ds, end)
            other_parts.append(data[pos:re])
            pos = re
            continue

        if pos + GRUP_HEADER_SIZE > end:
            other_parts.append(data[pos:end])
            break

        gs = struct.unpack_from("<I", data, pos + 4)[0]
        gt = struct.unpack_from("<I", data, pos + 12)[0]

        if gs < GRUP_HEADER_SIZE:
            other_parts.append(data[pos:end])
            break

        ge = min(pos + gs, end)

        if gt == 8:
            # Persistent Children
            persistent_grup_header = bytearray(data[pos:pos + GRUP_HEADER_SIZE])
            _split_persistent_records(
                data, pos + GRUP_HEADER_SIZE, ge, cell_fid,
                persistent_keep, persistent_move, stats
            )
        elif gt == 9:
            # Temporary Children
            temporary_grup_header = bytearray(data[pos:pos + GRUP_HEADER_SIZE])
            # 保留所有原有 Temporary 记录
            temporary_records.append(data[pos + GRUP_HEADER_SIZE:ge])
        else:
            other_parts.append(data[pos:ge])

        pos = ge

    # 重建 Persistent Children GRUP（只保留需要持久化的记录）
    result_parts = []

    if persistent_grup_header is not None:
        persistent_data = b"".join(persistent_keep)
        if persistent_data:
            new_p_size = GRUP_HEADER_SIZE + len(persistent_data)
            struct.pack_into("<I", persistent_grup_header, 4, new_p_size)
            result_parts.append(bytes(persistent_grup_header) + persistent_data)

    # 重建 Temporary Children GRUP（原有 + 移过来的）
    moved_data = b"".join(persistent_move)
    original_temp_data = b"".join(temporary_records)

    if temporary_grup_header is not None:
        temp_data = original_temp_data + moved_data
        new_t_size = GRUP_HEADER_SIZE + len(temp_data)
        struct.pack_into("<I", temporary_grup_header, 4, new_t_size)
        result_parts.append(bytes(temporary_grup_header) + temp_data)
    elif moved_data:
        # 没有现成的 Temporary GRUP，创建一个
        temp_header = bytearray(GRUP_HEADER_SIZE)
        temp_header[0:4] = b"GRUP"
        struct.pack_into("<I", temp_header, 4, GRUP_HEADER_SIZE + len(moved_data))
        temp_header[8:12] = struct.pack("<I", cell_fid)
        struct.pack_into("<I", temp_header, 12, 9)  # group_type = 9 (Temporary)
        result_parts.append(bytes(temp_header) + moved_data)

    result_parts.extend(other_parts)
    return b"".join(result_parts)


def _split_persistent_records(data: bytes, offset: int, end: int,
                              cell_fid: int,
                              keep: list, move: list, stats: dict):
    """将 Persistent GRUP 中的记录分为保留和移动两组。"""
    while offset < end:
        if offset + 4 > end:
            keep.append(data[offset:end])
            break

        rt = data[offset:offset + 4]

        if rt == b"GRUP":
            # Persistent 下不应该有嵌套 GRUP，但保险起见
            if offset + GRUP_HEADER_SIZE > end:
                keep.append(data[offset:end])
                break
            gs = struct.unpack_from("<I", data, offset + 4)[0]
            ge = min(offset + gs, end)
            keep.append(data[offset:ge])
            offset = ge
            continue

        if offset + RECORD_HEADER_SIZE > end:
            keep.append(data[offset:end])
            break

        ds = struct.unpack_from("<I", data, offset + 4)[0]
        fl = struct.unpack_from("<I", data, offset + 8)[0]
        fid = struct.unpack_from("<I", data, offset + 12)[0]
        rs = offset + RECORD_HEADER_SIZE
        re = min(rs + ds, end)

        rec_data = data[rs:re]
        record_bytes = data[offset:re]

        should_keep, reason = _should_keep_persistent(rt, fid, fl, rec_data)
        if should_keep:
            keep.append(record_bytes)
            stats["kept"] = stats.get("kept", 0) + 1
            stats.setdefault("kept_reasons", {})
            stats["kept_reasons"][reason] = stats["kept_reasons"].get(reason, 0) + 1
        else:
            move.append(record_bytes)
            cell_name = TARGET_CELLS.get(cell_fid, "unknown")
            stats["moved"] = stats.get("moved", 0) + 1
            stats.setdefault("moved_per_cell", {})
            stats["moved_per_cell"][cell_name] = stats["moved_per_cell"].get(cell_name, 0) + 1

        offset = re


def fix_persistent(input_path: str, output_path: str) -> dict:
    """修复 ESM 文件中错误的 Persistent REFR。

    返回修复统计信息。
    """
    with open(input_path, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        raise ValueError("不是有效的 ESM 文件")

    header_size = struct.unpack_from("<I", data, 4)[0]
    first_offset = RECORD_HEADER_SIZE + header_size

    if first_offset > len(data):
        raise ValueError("TES4 头部大小超出文件范围")

    tes4_part = data[:first_offset]

    stats = {"kept": 0, "moved": 0, "moved_per_cell": {}}
    records_part = _rewrite_with_fix(data, first_offset, len(data), {}, stats)

    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(tes4_part + records_part)

    return stats


def main():
    if len(sys.argv) < 3:
        print("用法: python -m tools.fix_persistent <input_esm> <output_esm>")
        print()
        print("修复 Worldspace Persistent Cell 中错误放置的 REFR 记录，")
        print("将不需要持久化的 REFR 移到 Temporary Cell Children 中。")
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    print(f"输入文件: {input_path}")
    print(f"输出文件: {output_path}")
    print()
    print("目标 Cell:")
    for fid, name in TARGET_CELLS.items():
        print(f"  {fid:08X}  {name}")
    print()

    stats = fix_persistent(input_path, output_path)

    print(f"修复完成!")
    print(f"  保留在 Persistent: {stats['kept']} 条")
    print(f"  移到 Temporary:    {stats['moved']} 条")
    print()
    if stats.get("moved_per_cell"):
        print("  各 Cell 移动数量:")
        for name, count in sorted(stats["moved_per_cell"].items()):
            print(f"    {name}: {count} 条")

    if stats.get("kept_reasons"):
        print("\n  保留原因统计:")
        for reason, count in sorted(stats["kept_reasons"].items(), key=lambda x: -x[1]):
            print(f"    {reason}: {count} 条")

    input_size = Path(input_path).stat().st_size
    output_size = Path(output_path).stat().st_size
    print(f"\n  文件大小: {input_size:,} -> {output_size:,} bytes")


if __name__ == "__main__":
    main()
