"""扫描 ESM 文件中所有包含可读文本的子记录类型，按 record_type + subrecord_type 组合展示。

使用多维启发式规则判断文本是否需要翻译：
- 文件路径检测
- 驼峰/下划线命名检测（内部标识符）
- 自然语言特征（空格比例、模板变量、句式）
- 文本长度
- 已知不需要翻译的子记录类型（EDID 等）

用法: python -m tools.scan_subrecords <esm_file_path>
"""

import re
import struct
import sys
import zlib
from collections import defaultdict

RECORD_HEADER_SIZE = 24
GRUP_HEADER_SIZE = 24
COMPRESSED_FLAG = 0x00040000
SUBRECORD_HEADER_SIZE = 6

KNOWN_TRANSLATABLE = frozenset({b"FULL", b"DESC", b"NNAM", b"SHRT", b"TNAM", b"RNAM"})

# 已知永远不需要翻译的子记录类型
KNOWN_INTERNAL = frozenset({"EDID", "MODL", "BFCB", "VMAD"})

# 驼峰命名模式: FooBar, fooBar
RE_CAMEL = re.compile(r"^[A-Za-z][a-z]+(?:[A-Z][a-z]+)+\d*$")
# 下划线命名模式: foo_bar, TMA_Radio01
RE_UNDERSCORE = re.compile(r"^[A-Za-z0-9]+(?:_[A-Za-z0-9]+)+$")
# 文件路径模式
RE_PATH = re.compile(r"[\\/]")
FILE_EXTENSIONS = (".nif", ".dds", ".mat", ".agx", ".rig", ".hkx", ".pex", ".bgsm", ".bto", ".btr", ".wav", ".xwm", ".fuz", ".lip")
# 模板变量: <Alias=xxx>
RE_TEMPLATE = re.compile(r"<Alias=[^>]+>")
# 纯数字或十六进制
RE_NUMERIC = re.compile(r"^[0-9A-Fa-f\-\.]+$")


def is_readable_text(data):
    """尝试将数据解码为可读文本，返回文本或 None。"""
    if len(data) == 0:
        return None
    if data.endswith(b"\x00"):
        data = data[:-1]
    if len(data) == 0:
        return None
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if "\ufffd" in text:
        return None
    printable = sum(1 for c in text if c.isprintable() or c in ("\n", "\r", "\t"))
    if len(text) > 0 and printable / len(text) >= 0.8 and len(text) >= 2:
        return text
    return None


def classify_single_text(text):
    """对单条文本进行分类，返回 (category, confidence) 元组。

    category: 'natural-lang' | 'identifier' | 'path' | 'binary-like' | 'enum-value' | 'unknown'
    confidence: 0.0 ~ 1.0
    """
    t = text.strip()
    if not t:
        return ("unknown", 0.0)

    # 1. 文件路径检测
    if RE_PATH.search(t) or any(t.lower().endswith(ext) for ext in FILE_EXTENSIONS):
        return ("path", 0.95)

    # 2. 不可打印字符 -> binary-like
    ctrl_count = sum(1 for c in t if ord(c) < 32 and c not in ("\n", "\r", "\t"))
    if ctrl_count > 0:
        return ("binary-like", 0.9)

    # 3. 纯数字/十六进制
    if RE_NUMERIC.match(t):
        return ("enum-value", 0.8)

    # 4. 模板变量 <Alias=xxx> -> 一定是玩家可见文本
    if RE_TEMPLATE.search(t):
        return ("natural-lang", 0.99)

    # 5. 包含换行的长文本 -> 大概率自然语言
    if "\n" in t and len(t) > 20:
        return ("natural-lang", 0.95)

    # 6. 驼峰/下划线命名 -> 内部标识符
    if RE_CAMEL.match(t) or RE_UNDERSCORE.match(t):
        return ("identifier", 0.9)

    # 7. 基于空格和词的分析
    words = t.split()
    space_ratio = t.count(" ") / len(t) if len(t) > 0 else 0
    avg_word_len = sum(len(w) for w in words) / len(words) if words else 0

    # 多个词，有合理的空格比例 -> 自然语言
    if len(words) >= 3 and space_ratio > 0.1:
        return ("natural-lang", 0.9)

    # 2 个词，看起来像名字或短语
    if len(words) == 2 and all(w[0].isupper() for w in words if w):
        return ("natural-lang", 0.75)

    # 单词但很短，没有空格
    if len(words) == 1:
        # 全大写短字符串 -> 可能是枚举
        if t.isupper() and len(t) <= 8:
            return ("enum-value", 0.7)
        # 首字母大写单词 -> 可能是名称也可能是标识符
        if t[0].isupper() and len(t) <= 20:
            return ("unknown", 0.5)
        # 包含中文 -> 自然语言
        if any("\u4e00" <= c <= "\u9fff" for c in t):
            return ("natural-lang", 0.95)

    # 2 个词但不确定
    if len(words) == 2:
        return ("unknown", 0.5)

    # 默认：有空格就偏向自然语言
    if space_ratio > 0.05 and len(t) > 5:
        return ("natural-lang", 0.6)

    return ("unknown", 0.4)


def classify_group(samples):
    """对一组样本进行综合分类。

    返回 (verdict, detail_str)
    verdict: 'TRANSLATE' | 'SKIP' | 'REVIEW'
    """
    categories = defaultdict(int)
    total_confidence = defaultdict(float)

    for _, text in samples:
        cat, conf = classify_single_text(text)
        categories[cat] += 1
        total_confidence[cat] += conf

    total = len(samples)
    if total == 0:
        return ("SKIP", "empty")

    # 计算各类别占比
    ratios = {cat: count / total for cat, count in categories.items()}

    # 路径占多数 -> SKIP
    if ratios.get("path", 0) > 0.5:
        return ("SKIP", "file-path %.0f%%" % (ratios["path"] * 100))

    # binary 占多数 -> SKIP
    if ratios.get("binary-like", 0) > 0.5:
        return ("SKIP", "binary-data %.0f%%" % (ratios["binary-like"] * 100))

    # 标识符占多数 -> SKIP
    if ratios.get("identifier", 0) > 0.5:
        return ("SKIP", "identifier %.0f%%" % (ratios["identifier"] * 100))

    # 枚举值占多数 -> SKIP
    if ratios.get("enum-value", 0) > 0.5:
        return ("SKIP", "enum-value %.0f%%" % (ratios["enum-value"] * 100))

    # 自然语言占多数 -> TRANSLATE
    if ratios.get("natural-lang", 0) > 0.5:
        return ("TRANSLATE", "natural-lang %.0f%%" % (ratios["natural-lang"] * 100))

    # 自然语言 + unknown 合计较高 -> REVIEW
    nl_ratio = ratios.get("natural-lang", 0) + ratios.get("unknown", 0) * 0.5
    if nl_ratio > 0.4:
        return ("REVIEW", "mixed (nl=%.0f%% unk=%.0f%%)" % (
            ratios.get("natural-lang", 0) * 100, ratios.get("unknown", 0) * 100))

    return ("SKIP", "non-text %.0f%%" % (
        (ratios.get("path", 0) + ratios.get("binary-like", 0) + ratios.get("identifier", 0) + ratios.get("enum-value", 0)) * 100))


def scan_subrecords(data, record_type, form_id, results):
    """扫描一条记录的所有子记录。"""
    offset = 0
    rec_str = record_type.decode("ascii", errors="replace")
    while offset + SUBRECORD_HEADER_SIZE <= len(data):
        sub_type = data[offset:offset + 4]
        sub_size = struct.unpack_from("<H", data, offset + 4)[0]
        offset += SUBRECORD_HEADER_SIZE
        if offset + sub_size > len(data):
            break
        sub_data = data[offset:offset + sub_size]
        text = is_readable_text(sub_data)
        if text:
            sub_str = sub_type.decode("ascii", errors="replace")
            key = (rec_str, sub_str)
            results[key].append((form_id, text[:200]))
        offset += sub_size


def scan_records(data, offset, end, results):
    """递归扫描所有记录。"""
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
            scan_records(data, offset + GRUP_HEADER_SIZE, group_end, results)
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

            rec_data = data[rec_start:rec_end]
            if flags & COMPRESSED_FLAG:
                if len(rec_data) >= 4:
                    decomp_size = struct.unpack_from("<I", rec_data, 0)[0]
                    try:
                        rec_data = zlib.decompress(rec_data[4:], bufsize=decomp_size)
                    except zlib.error:
                        offset = rec_end
                        continue

            scan_subrecords(rec_data, rec_type, form_id, results)
            offset = rec_end


def main():
    if len(sys.argv) < 2:
        print("用法: python -m tools.scan_subrecords <esm_file_path>")
        sys.exit(1)

    file_path = sys.argv[1]
    print("扫描文件: %s" % file_path)

    with open(file_path, "rb") as f:
        data = f.read()

    print("文件大小: {:,} bytes".format(len(data)))

    if len(data) < RECORD_HEADER_SIZE or data[0:4] != b"TES4":
        print("不是有效的 ESM 文件")
        sys.exit(1)

    header_size = struct.unpack_from("<I", data, 4)[0]
    start = RECORD_HEADER_SIZE + header_size

    results = defaultdict(list)
    scan_records(data, start, len(data), results)

    # 分类
    translate_list = []
    review_list = []
    skip_list = []

    for (rec_type, sub_type), samples in sorted(results.items()):
        # 已知内部类型直接跳过
        if sub_type in KNOWN_INTERNAL:
            skip_list.append((rec_type, sub_type, len(samples), "known-internal", samples))
            continue

        verdict, detail = classify_group(samples)
        known = sub_type.encode("ascii", errors="replace") in KNOWN_TRANSLATABLE
        entry = (rec_type, sub_type, len(samples), detail, samples, known)

        if verdict == "TRANSLATE":
            translate_list.append(entry)
        elif verdict == "REVIEW":
            review_list.append(entry)
        else:
            skip_list.append((rec_type, sub_type, len(samples), detail, samples))

    # 输出: TRANSLATE
    print("\n" + "=" * 80)
    print("  需要翻译 (TRANSLATE)")
    print("=" * 80)
    for rec_type, sub_type, count, detail, samples, known in translate_list:
        tag = "已收录" if known else "未收录"
        print("\n  [%s -> %s] %d 条  <%s>  判定: %s" % (rec_type, sub_type, count, tag, detail))
        for fid, text in samples[:3]:
            print("    %08X: %s" % (fid, text.replace("\n", "\\n")[:80]))
        if count > 3:
            print("    ... 还有 %d 条" % (count - 3))

    # 输出: REVIEW
    if review_list:
        print("\n" + "=" * 80)
        print("  需要人工确认 (REVIEW)")
        print("=" * 80)
        for rec_type, sub_type, count, detail, samples, known in review_list:
            tag = "已收录" if known else "未收录"
            print("\n  [%s -> %s] %d 条  <%s>  判定: %s" % (rec_type, sub_type, count, tag, detail))
            for fid, text in samples[:5]:
                print("    %08X: %s" % (fid, text.replace("\n", "\\n")[:80]))
            if count > 5:
                print("    ... 还有 %d 条" % (count - 5))

    # 输出: SKIP
    print("\n" + "=" * 80)
    print("  跳过 (SKIP)")
    print("=" * 80)
    for entry in skip_list:
        rec_type, sub_type, count, detail = entry[0], entry[1], entry[2], entry[3]
        print("  [%s -> %s] %d 条  (%s)" % (rec_type, sub_type, count, detail))

    # 汇总
    t_count = sum(e[2] for e in translate_list)
    r_count = sum(e[2] for e in review_list)
    s_count = sum(e[2] for e in skip_list)
    print("\n" + "-" * 80)
    print("汇总:")
    print("  TRANSLATE: %d 种组合, %d 条记录" % (len(translate_list), t_count))
    print("  REVIEW:    %d 种组合, %d 条记录" % (len(review_list), r_count))
    print("  SKIP:      %d 种组合, %d 条记录" % (len(skip_list), s_count))

    # 列出未收录但需要翻译的组合
    missing = [(r, s) for r, s, _, _, _, known in translate_list if not known]
    if missing:
        print("\n  未收录但需要翻译的组合:")
        for r, s in missing:
            print("    %s -> %s" % (r, s))


if __name__ == "__main__":
    main()
