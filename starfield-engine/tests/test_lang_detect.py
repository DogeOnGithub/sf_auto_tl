"""已汉化判定单元测试。"""
from __future__ import annotations

from engine.esm_parser import StringRecord
from engine.lang_detect import (
    carries_language_signal,
    contains_chinese,
    measure_chinese_ratio,
)


class TestContainsChinese:
    """中文字符判定测试。

    口径必须与 Java 侧 TranslationCacheService.CHINESE_PATTERN 一致，
    否则会出现「引擎认为要翻、Java 认为是中文不给存缓存」这种永不命中缓存的组合。
    """

    def test_pure_chinese(self):
        """纯中文文本判为中文。"""
        assert contains_chinese("星际武器") is True

    def test_mixed_text_counts_as_chinese(self):
        """中英混排只要含中文就算已翻译 mod 里的专有名词常保留英文。"""
        assert contains_chinese("UC 星舰服务处") is True

    def test_pure_english(self):
        """纯英文不算中文。"""
        assert contains_chinese("Lambent Poppy") is False

    def test_empty(self):
        """空串不算中文。"""
        assert contains_chinese("") is False


class TestCarriesLanguageSignal:
    """语言信号判定测试。

    只有带语言信号的词条才进入占比统计的分母，分母越干净阈值才越可靠。
    """

    def test_chinese_carries_signal(self):
        """中文带信号。"""
        assert carries_language_signal("星际武器") is True

    def test_english_word_carries_signal(self):
        """英文词带信号。"""
        assert carries_language_signal("Lambent Poppy") is True

    def test_pure_placeholder_carries_no_signal(self):
        """整条都是 Bethesda 占位符的词条不带信号。

        <p>这类词条在一个 mod 里可能有成百上千条 且永远是拉丁字母、永远不需要翻译。
        算进分母会把已汉化文件的中文占比压下去 让阈值失效。
        """
        assert carries_language_signal("<Alias.CurrentName=Crew01><Token=CommentsOn0>") is False

    def test_double_brace_placeholder_carries_no_signal(self):
        """{{...}} 富文本标记同样不带信号。"""
        assert carries_language_signal("{{TAG_ITEM}}") is False

    def test_binary_residue_carries_no_signal(self):
        """二进制数据经 windows-1252 兜底解码后的残渣不带信号。

        <p>形如 'oŸ)' 的串里只有一个孤立拉丁字母 不能当英文看待，
        这也是「拉丁词」要求连续两个以上字母的原因。
        """
        assert carries_language_signal("oŸ)") is False

    def test_symbols_and_digits_carry_no_signal(self):
        """纯符号数字不带信号。"""
        assert carries_language_signal("-- 123 --") is False

    def test_whitespace_carries_no_signal(self):
        """空白串不带信号。"""
        assert carries_language_signal("   ") is False


class TestMeasureChineseRatio:
    """中文占比统计测试。"""

    def test_all_chinese(self):
        """全中文时占比为 1。"""
        records = [StringRecord(record_id=f"NPC_:{i}:FULL", text="星际武器") for i in range(5)]

        detectable, chinese, ratio = measure_chinese_ratio(records)

        assert (detectable, chinese, ratio) == (5, 5, 1.0)

    def test_all_english(self):
        """全英文时占比为 0。"""
        records = [StringRecord(record_id=f"NPC_:{i}:FULL", text="Lambent Poppy") for i in range(5)]

        detectable, chinese, ratio = measure_chinese_ratio(records)

        assert (detectable, chinese, ratio) == (5, 0, 0.0)

    def test_placeholders_excluded_from_denominator(self):
        """占位符词条不进分母 否则占比会被稀释到阈值以下。"""
        records = [StringRecord(record_id="NPC_:1:FULL", text="星际武器")]
        records += [
            StringRecord(record_id=f"TMLM:{i}:ITXT", text=f"<Token=CommentsOn{i}>")
            for i in range(9)
        ]

        detectable, chinese, ratio = measure_chinese_ratio(records)

        assert (detectable, chinese, ratio) == (1, 1, 1.0)

    def test_empty_records_return_zero_ratio(self):
        """空列表返回占比 0 让调用方按「无法判定」处理而不是按「已汉化」拦截。"""
        assert measure_chinese_ratio([]) == (0, 0, 0.0)

    def test_no_detectable_records_return_zero_ratio(self):
        """全是不带信号的词条时同样返回 0 不能除零也不能误判成已汉化。"""
        records = [StringRecord(record_id=f"AFFE:{i}:NNAM", text="oŸ)") for i in range(5)]

        assert measure_chinese_ratio(records) == (0, 0, 0.0)

    def test_partial_translation_ratio(self):
        """半成品文件的占比落在阈值之下 应放行去翻剩下的英文。"""
        records = [StringRecord(record_id=f"NPC_:{i}:FULL", text="星际武器") for i in range(6)]
        records += [StringRecord(record_id=f"NPC_:1{i}:FULL", text="Lambent Poppy") for i in range(4)]

        detectable, chinese, ratio = measure_chinese_ratio(records)

        assert (detectable, chinese) == (10, 6)
        assert ratio == 0.6
