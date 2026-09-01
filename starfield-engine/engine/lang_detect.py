"""判断待翻译文本是否已经是简体中文。

<p>存在的原因：线上大量用户把「已经汉化过的 mod」重新提交翻译。这类文件每一条词条都已是中文，
送进 LLM 只会拿回一份和原文几乎一样的结果，但 token 照价扣。更糟的是缓存救不了它——
Java 侧 TranslationCacheService.save 会主动丢弃「原文含中文」的条目（避免污染缓存表），
所以同一个已汉化文件提交 N 次就是 N 次全额付费，一次都不会命中缓存。

<p>中文判定口径统一用 CJK 统一表意文字区间 [\\u4e00-\\u9fff]，与 Java 侧
TranslationCacheService.CHINESE_PATTERN 保持一致。两边口径必须相同，否则会出现
「引擎认为要翻、Java 认为是中文不给存缓存」的组合，那正是上面说的永不命中缓存的坑。
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger(__name__)

# CJK 统一表意文字 与 Java 侧 TranslationCacheService.CHINESE_PATTERN 同口径
CJK_PATTERN = re.compile(r"[\u4e00-\u9fff]")

# 连续两个以上拉丁字母才算「拉丁词」
# 单个字母不算：二进制数据经 windows-1252 兜底解码后常见形如 'oŸ)' 的串，
# 里头那个孤立的 'o' 会被误判成英文，把已汉化文件的中文占比稀释下去。
LATIN_WORD_PATTERN = re.compile(r"[A-Za-z]{2,}")

# Bethesda 文本里的占位符与富文本标记 判定语言前先剔除
# <Alias.CurrentName=Crew01><Token=CommentsOn0> 这类整条都是占位符的词条在 mod 里成百上千条，
# 它们永远是「拉丁字母」但永远不需要翻译。不剔除的话一个 100% 汉化的文件也可能被算成 70% 中文，
# 阈值就永远拦不住。
PLACEHOLDER_PATTERNS = (
    re.compile(r"<[^<>]*>"),
    re.compile(r"\{\{[^{}]*\}\}"),
)


def contains_chinese(text: str) -> bool:
    """判断文本是否含中文字符。

    Args:
        text: 待判断的文本。

    Returns:
        含任意 CJK 表意文字返回 True。
    """
    return bool(text) and bool(CJK_PATTERN.search(text))


def _strip_placeholders(text: str) -> str:
    """剔除占位符与富文本标记 只留下真正会展示给玩家的文本。

    Args:
        text: 原始文本。

    Returns:
        剔除标记后的文本。
    """
    for pattern in PLACEHOLDER_PATTERNS:
        text = pattern.sub(" ", text)
    return text


def carries_language_signal(text: str) -> bool:
    """判断文本是否带有可用于语言判定的信号。

    <p>只有「含中文」或「剔除占位符后仍有拉丁词」的词条才进入占比统计的分母。
    纯符号、纯数字、二进制误解码残渣、整条都是 <Token=...> 占位符的词条一律排除：
    它们既不能证明文件已汉化 也不能证明文件还是英文 留在分母里只会把占比往下拉。

    Args:
        text: 待判断的文本。

    Returns:
        可用于语言判定返回 True。
    """
    if not text or not text.strip():
        return False
    if contains_chinese(text):
        return True
    return bool(LATIN_WORD_PATTERN.search(_strip_placeholders(text)))


def measure_chinese_ratio(records) -> tuple[int, int, float]:
    """统计词条集合中已是中文的比例。

    <p>分母是「带语言信号的词条数」而不是词条总数，理由见 carries_language_signal。
    分母为 0 时返回占比 0，让调用方按「无法判定」处理而不是按「已汉化」拦截。

    Args:
        records: StringRecord 列表 需有 text 属性。

    Returns:
        (带语言信号的词条数, 其中已是中文的词条数, 中文占比)。
    """
    detectable = 0
    chinese = 0
    for record in records:
        if not carries_language_signal(record.text):
            continue
        detectable += 1
        if contains_chinese(record.text):
            chinese += 1
    ratio = chinese / detectable if detectable > 0 else 0.0
    return detectable, chinese, ratio
