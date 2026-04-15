"""
扫描 ESM 文件，检查是否开启了本地化（Localized strings）。

Bethesda 插件格式（TES4 record）的 record flags 中，bit 7 (0x80) 表示 Localized。
当该标志位为 1 时，插件使用外部 .STRINGS/.DLSTRINGS/.ILSTRINGS 文件存储文本，
而非将字符串内联在记录中。

用法: python scan_esm_localization.py <esm_file_path>
"""

import struct
import sys
from pathlib import Path


# TES4 record flags 中的 Localized 标志位
LOCALIZED_FLAG = 0x00000080


def scan_esm(file_path: str) -> dict:
    """扫描 ESM 文件头，提取本地化相关信息"""
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"文件不存在: {file_path}")

    with open(path, "rb") as f:
        # 读取 record type (4 bytes) — 应该是 TES4
        record_type = f.read(4)
        if record_type != b"TES4":
            raise ValueError(f"不是有效的 ESM/ESP 文件，首记录类型为: {record_type}")

        # data size (4 bytes, uint32 LE)
        data_size = struct.unpack("<I", f.read(4))[0]

        # record flags (4 bytes, uint32 LE)
        record_flags = struct.unpack("<I", f.read(4))[0]

        # form ID (4 bytes)
        form_id = struct.unpack("<I", f.read(4))[0]

        # version control info (4 bytes)
        vc_info = f.read(4)

        # internal version (2 bytes, uint16 LE)
        internal_version = struct.unpack("<H", f.read(2))[0]

        # unknown (2 bytes)
        f.read(2)

        is_localized = bool(record_flags & LOCALIZED_FLAG)
        is_master = bool(record_flags & 0x01)
        is_light = bool(record_flags & 0x00000200)

        # 尝试读取 HEDR 子记录获取更多信息
        hedr_info = {}
        if data_size > 0:
            sub_type = f.read(4)
            if sub_type == b"HEDR":
                sub_size = struct.unpack("<H", f.read(2))[0]
                if sub_size >= 12:
                    version = struct.unpack("<f", f.read(4))[0]
                    num_records = struct.unpack("<I", f.read(4))[0]
                    next_object_id = struct.unpack("<I", f.read(4))[0]
                    hedr_info = {
                        "version": round(version, 2),
                        "num_records": num_records,
                        "next_object_id": next_object_id,
                    }

        return {
            "file": str(path.name),
            "file_size_mb": round(path.stat().st_size / (1024 * 1024), 2),
            "record_flags": f"0x{record_flags:08X}",
            "is_localized": is_localized,
            "is_master": is_master,
            "is_light": is_light,
            "internal_version": internal_version,
            **hedr_info,
        }


def main():
    if len(sys.argv) < 2:
        print("用法: python scan_esm_localization.py <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    print(f"扫描文件: {file_path}\n")

    try:
        result = scan_esm(file_path)

        print("=" * 50)
        print(f"  文件名:       {result['file']}")
        print(f"  文件大小:     {result['file_size_mb']} MB")
        print(f"  Record Flags: {result['record_flags']}")
        print(f"  内部版本:     {result['internal_version']}")
        if "version" in result:
            print(f"  插件版本:     {result['version']}")
            print(f"  记录数量:     {result['num_records']}")
        print(f"  是否 Master:  {'是' if result['is_master'] else '否'}")
        print(f"  是否 Light:   {'是' if result['is_light'] else '否'}")
        print("=" * 50)

        if result["is_localized"]:
            print("\n✅ 本地化已开启 (Localized flag = 1)")
            print("   该插件使用外部 .STRINGS/.DLSTRINGS/.ILSTRINGS 文件存储文本")
            print("   翻译时需要处理 Strings 文件而非内联字符串")
        else:
            print("\n❌ 本地化未开启 (Localized flag = 0)")
            print("   该插件将字符串内联存储在 ESM 文件中")
            print("   翻译时直接修改 ESM 文件中的字符串即可")

    except Exception as e:
        print(f"错误: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
