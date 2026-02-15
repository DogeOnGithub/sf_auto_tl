"""PromptBuilder 单元测试。"""

import pytest

from engine.prompt_builder import DEFAULT_PROMPT, build_prompt


class TestBuildPromptDefaultPrompt:
    """无自定义 Prompt 时使用默认模板。"""

    def test_uses_default_prompt_when_custom_is_none(self):
        """custom_prompt 为 None 时应使用默认模板。"""
        result = build_prompt(texts_to_translate=["Hello world"])
        assert DEFAULT_PROMPT in result

    def test_uses_default_prompt_when_custom_is_empty(self):
        """custom_prompt 为空字符串时应使用默认模板。"""
        result = build_prompt(texts_to_translate=["Hello world"], custom_prompt="")
        assert DEFAULT_PROMPT in result


class TestBuildPromptCustomPrompt:
    """有自定义 Prompt 时替代默认模板。"""

    def test_uses_custom_prompt(self):
        """设置 custom_prompt 时应使用自定义内容。"""
        custom = "请用文言文风格翻译以下文本"
        result = build_prompt(texts_to_translate=["Hello"], custom_prompt=custom)
        assert custom in result
        assert DEFAULT_PROMPT not in result

    def test_custom_prompt_replaces_default(self):
        """自定义 Prompt 应完全替代默认模板。"""
        custom = "Translate to Japanese"
        result = build_prompt(texts_to_translate=["Test"], custom_prompt=custom)
        assert result.startswith(custom)


class TestBuildPromptDictionary:
    """词典约束段测试。"""

    def test_includes_dictionary_entries(self):
        """词典非空时应追加词条约束。"""
        entries = [
            {"sourceText": "Dragonborn", "targetText": "龙裔"},
            {"sourceText": "Stormcloak", "targetText": "风暴斗篷"},
        ]
        result = build_prompt(texts_to_translate=["Hello"], dictionary_entries=entries)
        assert "以下词条必须保持指定翻译：" in result
        assert "Dragonborn → 龙裔" in result
        assert "Stormcloak → 风暴斗篷" in result

    def test_no_dictionary_section_when_empty(self):
        """词典为空列表时不应追加词典段。"""
        result = build_prompt(texts_to_translate=["Hello"], dictionary_entries=[])
        assert "以下词条必须保持指定翻译：" not in result

    def test_no_dictionary_section_when_none(self):
        """词典为 None 时不应追加词典段。"""
        result = build_prompt(texts_to_translate=["Hello"], dictionary_entries=None)
        assert "以下词条必须保持指定翻译：" not in result

    def test_skips_entries_with_empty_source(self):
        """sourceText 为空的词条应被跳过。"""
        entries = [
            {"sourceText": "", "targetText": "龙裔"},
            {"sourceText": "Stormcloak", "targetText": "风暴斗篷"},
        ]
        result = build_prompt(texts_to_translate=["Hello"], dictionary_entries=entries)
        assert "龙裔" not in result.split("待翻译文本")[0] or "→ 龙裔" not in result
        assert "Stormcloak → 风暴斗篷" in result

    def test_skips_entries_with_empty_target(self):
        """targetText 为空的词条应被跳过。"""
        entries = [
            {"sourceText": "Dragonborn", "targetText": ""},
        ]
        result = build_prompt(texts_to_translate=["Hello"], dictionary_entries=entries)
        assert "Dragonborn →" not in result


class TestBuildPromptTexts:
    """待翻译文本段测试。"""

    def test_includes_texts_to_translate(self):
        """待翻译文本应出现在 Prompt 中。"""
        texts = ["Iron Sword", "Steel Shield"]
        result = build_prompt(texts_to_translate=texts)
        assert "待翻译文本：" in result
        assert "Iron Sword" in result
        assert "Steel Shield" in result

    def test_texts_joined_by_newline(self):
        """多条待翻译文本应以换行分隔。"""
        texts = ["Line1", "Line2", "Line3"]
        result = build_prompt(texts_to_translate=texts)
        assert "Line1\nLine2\nLine3" in result

    def test_empty_texts_list(self):
        """空文本列表应仍然包含文本段头部。"""
        result = build_prompt(texts_to_translate=[])
        assert "待翻译文本：" in result


class TestBuildPromptAssemblyOrder:
    """Prompt 组装顺序测试。"""

    def test_order_base_dict_text(self):
        """组装顺序应为：基础指令 → 词典约束 → 待翻译文本。"""
        custom = "自定义指令"
        entries = [{"sourceText": "A", "targetText": "甲"}]
        texts = ["Hello"]

        result = build_prompt(
            texts_to_translate=texts,
            custom_prompt=custom,
            dictionary_entries=entries,
        )

        idx_custom = result.index(custom)
        idx_dict = result.index("以下词条必须保持指定翻译：")
        idx_text = result.index("待翻译文本：")

        assert idx_custom < idx_dict < idx_text

    def test_order_base_text_without_dict(self):
        """无词典时顺序应为：基础指令 → 待翻译文本。"""
        result = build_prompt(texts_to_translate=["Hello"])

        idx_default = result.index(DEFAULT_PROMPT)
        idx_text = result.index("待翻译文本：")

        assert idx_default < idx_text
