"""扫描 ESM 文件中所有 REFR 记录，列出引用的 Base Object 及其出现次数。

用于定位 MOD 中不该出现的杂物（垃圾桶、盘子等），找到它们的 Base FormID 后
可以用 remove_refr.py 批量删除。

用法: python -m tools.scan_refr <esm_file_path>
"""

import struct
import sys
import zlib
from collections import defaultdict

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _decode_text(data: bytes) -> str:
    """解码 null 结尾的文本。"""
    if data.endswith(b"\x00"):
        data = data[:-1]
    return data.decode("utf-8", errors="replace")


def _parse_refr_subrecords(data: bytes) -> dict:
    """解析 REFR 记录的子记录，提取关键字段。"""
    result = {"edid": "", "name_formid": 0, "full": "", "position": None}
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

        if sub_type == b"EDID" and sub_size > 0:
            result["edid"] = _decode_text(sub_data)
        elif sub_type == b"NAME" and sub_size >= 4:
            result["name_formid"] = struct.unpack_from("<I", sub_data, 0)[0]
        elif sub_type == b"FULL" and sub_size > 0:
            result["full"] = _decode_text(sub_data)
        elif sub_type == b"DATA" and sub_size >= 12:
            # 位置数据: x, y, z (float)
            x = struct.unpack_from("<f", sub_data, 0)[0]
            y = struct.unpack_from("<f", sub_data, 4)[0]
            z = struct.unpack_from("<f", sub_data, 8)[0]
            result["position"] = (x, y, z)

        offset += sub_size

    return result


def scan_records(data: bytes, offset: int, end: int, refr_list: list):
    """递归扫描所有记录，收集 REFR 信息。"""
    while offset < end:
        if offset + 4 > end:
            break
        rec_type = data[offset:offset + 4]

        if rec_type == b"GRUP":
            if offset + GRUP_HEADER_SIZE > end:
                break
            group_size = struct.unpack_from("<I", data, offset + 4)[0]
            if group_size < GRUP_HEADER_SIZE:
                break
            group_end = min(offset + group_size, end)
            scan_records(data, offset + GRUP_HEADER_SIZE, group_end, refr_list)
            offset = group_end
        else:
            if offset + RECORD_HEADER_SIZE > end:
                break
            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = rec_start + data_size
            if rec_end > end:
                break

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
                    else:
                        offset = rec_end
                        continue

                info = _parse_refr_subrecords(rec_data)
                info["form_id"] = form_id
                info["flags"] = flags
                refr_list.append(info)

            offset = rec_end


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.scan_refr <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    print(f"扫描 REFR 记录: {file_path}")

    with open(file_path, "rb") as f:
        data = f.read()

    print(f"文件大小: {len(data):,} bytes")

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    refr_list = []
    scan_records(data, start, len(data), refr_list)

    print(f"\n共找到 {len(refr_list)} 条 REFR 记录\n")

    # 按 Base FormID 分组统计
    base_groups = defaultdict(list)
    for info in refr_list:
        base_groups[info["name_formid"]].append(info)

    # 按出现次数降序排列
    sorted_groups = sorted(base_groups.items(), key=lambda x: len(x[1]), reverse=True)

    print(f"{'Base FormID':>12}  {'数量':>5}  {'示例 REFR FormID':>16}  {'Editor ID / 名称'}")
    print("-" * 80)

    for base_fid, refs in sorted_groups:
        sample = refs[0]
        label = sample["edid"] or sample["full"] or "(无名称)"
        sample_refr_fid = sample["form_id"]
        print(f"  {base_fid:08X}    {len(refs):>4}    {sample_refr_fid:08X}          {label}")

    # 汇总
    print(f"\n共 {len(base_groups)} 种 Base Object, {len(refr_list)} 条 REFR 记录")
    print("\n提示: 找到杂物的 Base FormID 后，使用以下命令删除:")
    print("  python -m tools.remove_refr <esm_file> <output_file> <base_formid1> [base_formid2 ...]")


if __name__ == "__main__":
    main()
