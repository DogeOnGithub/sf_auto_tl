"""从 ESM 文件中删除指定 Base FormID 的 REFR 记录。

用于修复 MOD 中不该出现的杂物（垃圾桶、盘子等）。先用 scan_refr.py 找到
杂物的 Base FormID，再用本工具批量删除。

支持两种指定方式：
  1. 命令行直接传入: python -m tools.remove_refr input.esm output.esm 0012AB 0034CD
  2. 从文件读取: python -m tools.remove_refr input.esm output.esm --file formids.txt
     文件每行一个 FormID（十六进制），支持 # 注释和空行

用法:
  python -m tools.remove_refr <input_esm> <output_esm> <base_formid1> [base_formid2 ...]
  python -m tools.remove_refr <input_esm> <output_esm> --file <formid_list_file>
"""

import struct
import sys
import zlib
from pathlib import Path

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6


def _get_refr_base_formid(data: bytes, flags: int) -> int | None:
    """从 REFR 记录数据中提取 NAME 子记录的 Base FormID。"""
    rec_data = data
    if flags & COMPRESSED_FLAG:
        if len(rec_data) < 4:
            return None
        decomp_size = struct.unpack_from("<I", rec_data, 0)[0]
        try:
            rec_data = zlib.decompress(rec_data[4:], bufsize=decomp_size)
        except zlib.error:
            return None

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

    return None


def _rewrite_records(
    data: bytes,
    offset: int,
    end: int,
    remove_base_fids: set[int],
    removed: list[int],
) -> bytes:
    """递归重写记录，跳过匹配的 REFR 记录并调整 GRUP 大小。"""
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
            group_end = min(offset + group_size, end)

            # 保留 GRUP 头部，递归处理内部
            grup_header = bytearray(data[offset:offset + GRUP_HEADER_SIZE])
            inner_data = _rewrite_records(
                data, offset + GRUP_HEADER_SIZE, group_end, remove_base_fids, removed,
            )

            # 如果 GRUP 内部为空（所有记录都被删除），跳过整个 GRUP
            if len(inner_data) == 0:
                offset = group_end
                continue

            # 更新 group_size
            new_group_size = GRUP_HEADER_SIZE + len(inner_data)
            struct.pack_into("<I", grup_header, 4, new_group_size)
            parts.append(bytes(grup_header) + inner_data)
            offset = group_end

        else:
            if offset + RECORD_HEADER_SIZE > end:
                parts.append(data[offset:end])
                break

            data_size = struct.unpack_from("<I", data, offset + 4)[0]
            flags = struct.unpack_from("<I", data, offset + 8)[0]
            form_id = struct.unpack_from("<I", data, offset + 12)[0]
            rec_start = offset + RECORD_HEADER_SIZE
            rec_end = min(rec_start + data_size, end)

            if rec_type == b"REFR":
                rec_data = data[rec_start:rec_end]
                base_fid = _get_refr_base_formid(rec_data, flags)
                if base_fid is not None and base_fid in remove_base_fids:
                    # 跳过这条 REFR 记录
                    removed.append(form_id)
                    offset = rec_end
                    continue

            # 保留记录
            parts.append(data[offset:rec_end])
            offset = rec_end

    return b"".join(parts)


def remove_refr(input_path: str, output_path: str, base_formids: set[int]) -> list[int]:
    """从 ESM 文件中删除指定 Base FormID 的 REFR 记录。

    返回被删除的 REFR FormID 列表。
    """
    with open(input_path, "rb") as f:
        data = f.read()

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        raise ValueError("不是有效的 ESM 文件")

    header_size = struct.unpack_from("<I", data, 4)[0]
    first_offset = RECORD_HEADER_SIZE + header_size

    if first_offset > len(data):
        raise ValueError("TES4 头部大小超出文件范围")

    # TES4 头部保持不变
    tes4_part = data[:first_offset]

    removed: list[int] = []
    records_part = _rewrite_records(data, first_offset, len(data), base_formids, removed)

    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(tes4_part + records_part)

    return removed


def _parse_formid_file(file_path: str) -> set[int]:
    """从文件中读取 FormID 列表，每行一个十六进制值，支持 # 注释。"""
    formids = set()
    with open(file_path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            # 支持 0x 前缀
            line = line.split("#")[0].strip()
            if line:
                formids.add(int(line, 16))
    return formids


def main():
    if len(sys.argv) < 4:
        print("用法:")
        print("  python -m tools.remove_refr <input_esm> <output_esm> <base_formid1> [base_formid2 ...]")
        print("  python -m tools.remove_refr <input_esm> <output_esm> --file <formid_list_file>")
        print()
        print("FormID 为十六进制，例如: 0012AB34")
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    if sys.argv[3] == "--file":
        if len(sys.argv) < 5:
            print("错误: --file 后需要指定文件路径")
            sys.exit(1)
        base_formids = _parse_formid_file(sys.argv[4])
    else:
        base_formids = set()
        for arg in sys.argv[3:]:
            base_formids.add(int(arg, 16))

    print(f"输入文件: {input_path}")
    print(f"输出文件: {output_path}")
    print(f"要删除的 Base FormID ({len(base_formids)} 个):")
    for fid in sorted(base_formids):
        print(f"  {fid:08X}")

    removed = remove_refr(input_path, output_path, base_formids)

    print(f"\n完成! 共删除 {len(removed)} 条 REFR 记录")
    if removed:
        print("被删除的 REFR FormID:")
        for fid in removed:
            print(f"  {fid:08X}")

    input_size = Path(input_path).stat().st_size
    output_size = Path(output_path).stat().st_size
    print(f"\n文件大小: {input_size:,} -> {output_size:,} bytes (减少 {input_size - output_size:,} bytes)")


if __name__ == "__main__":
    main()
