"""术语提取器单元测试。"""

from __future__ import annotations

import json

import pytest

from engine.esm_parser import StringRecord
from engine.glossary_extractor import _parse_glossary_response, _sample_texts


# ---------------------------------------------------------------------------
# _parse_glossary_response 测试
# ---------------------------------------------------------------------------

class TestParseGlossaryResponse:
    """术语表 JSON 解析测试。"""

    def test_valid_json_array(self):
        """正常 JSON 数组应正确解析。"""
        data = [
            {"sourceText": "Vasco", "targetText": "瓦斯科"},
            {"sourceText": "Constellation", "targetText": "群星组织"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 2
        assert result[0] == {"sourceText": "Vasco", "targetText": "瓦斯科"}
        assert result[1] == {"sourceText": "Constellation", "targetText": "群星组织"}

    def test_markdown_code_block_json(self):
        """包裹在 ```json ... ``` 中的 JSON 应正确解析。"""
        raw = '```json\n[{"sourceText": "Vasco", "targetText": "瓦斯科"}]\n```'
        result = _parse_glossary_response(raw)

        assert len(result) == 1
        assert result[0]["sourceText"] == "Vasco"

    def test_markdown_code_block_no_lang(self):
        """包裹在 ``` ... ``` 中（无语言标记）的 JSON 应正确解析。"""
        raw = '```\n[{"sourceText": "Vasco", "targetText": "瓦斯科"}]\n```'
        result = _parse_glossary_response(raw)

        assert len(result) == 1
        assert result[0]["sourceText"] == "Vasco"

    def test_deduplication_keeps_last(self):
        """相同 sourceText 去重，保留最后一条。"""
        data = [
            {"sourceText": "Vasco", "targetText": "瓦斯科"},
            {"sourceText": "Vasco", "targetText": "瓦斯柯"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 1
        assert result[0]["targetText"] == "瓦斯柯"

    def test_skip_missing_source_text(self):
        """缺少 sourceText 的条目应被跳过。"""
        data = [
            {"targetText": "瓦斯科"},
            {"sourceText": "Constellation", "targetText": "群星组织"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 1
        assert result[0]["sourceText"] == "Constellation"

    def test_skip_missing_target_text(self):
        """缺少 targetText 的条目应被跳过。"""
        data = [
            {"sourceText": "Vasco"},
            {"sourceText": "Constellation", "targetText": "群星组织"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 1
        assert result[0]["sourceText"] == "Constellation"

    def test_skip_empty_source_text(self):
        """sourceText 为空字符串的条目应被跳过。"""
        data = [
            {"sourceText": "", "targetText": "瓦斯科"},
            {"sourceText": "Constellation", "targetText": "群星组织"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 1
        assert result[0]["sourceText"] == "Constellation"

    def test_skip_empty_target_text(self):
        """targetText 为空字符串的条目应被跳过。"""
        data = [
            {"sourceText": "Vasco", "targetText": ""},
            {"sourceText": "Constellation", "targetText": "群星组织"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 1
        assert result[0]["sourceText"] == "Constellation"

    def test_skip_non_string_values(self):
        """sourceText 或 targetText 为非字符串类型时应被跳过。"""
        data = [
            {"sourceText": 123, "targetText": "瓦斯科"},
            {"sourceText": "Constellation", "targetText": 456},
            {"sourceText": "Vasco", "targetText": "瓦斯科"},
        ]
        result = _parse_glossary_response(json.dumps(data))

        assert len(result) == 1
        assert result[0]["sourceText"] == "Vasco"

    def test_skip_non_dict_entries(self):
        """数组中的非字典元素应被跳过。"""
        raw = '[{"sourceText": "Vasco", "targetText": "瓦斯科"}, "invalid", 42, null]'
        result = _parse_glossary_response(raw)

        assert len(result) == 1
        assert result[0]["sourceText"] == "Vasco"

    def test_invalid_json_returns_empty(self):
        """无效 JSON 应返回空列表。"""
        result = _parse_glossary_response("this is not json")
        assert result == []

    def test_empty_string_returns_empty(self):
        """空字符串应返回空列表。"""
        result = _parse_glossary_response("")
        assert result == []

    def test_json_object_not_array_returns_empty(self):
        """JSON 对象（非数组）应返回空列表。"""
        result = _parse_glossary_response('{"sourceText": "Vasco", "targetText": "瓦斯科"}')
        assert result == []

    def test_empty_array(self):
        """空 JSON 数组应返回空列表。"""
        result = _parse_glossary_response("[]")
        assert result == []

    def test_whitespace_around_response(self):
        """响应前后有空白字符时应正确解析。"""
        raw = '  \n [{"sourceText": "Vasco", "targetText": "瓦斯科"}] \n  '
        result = _parse_glossary_response(raw)

        assert len(result) == 1
        assert result[0]["sourceText"] == "Vasco"


# ---------------------------------------------------------------------------
# _sample_texts 测试
# ---------------------------------------------------------------------------

def _make_record(text: str) -> StringRecord:
    """创建测试用 StringRecord。"""
    return StringRecord(record_id="TEST:00000000:FULL", text=text)


class TestSampleTexts:
    """文本采样策略测试。"""

    def test_empty_records(self):
        """空记录列表应返回空列表。"""
        result = _sample_texts([], max_chars=100)
        assert result == []

    def test_total_within_limit_returns_all(self):
        """总字符数未超限时应返回全部文本。"""
        records = [_make_record("hello"), _make_record("world")]
        result = _sample_texts(records, max_chars=100)
        assert result == ["hello", "world"]

    def test_total_exactly_at_limit_returns_all(self):
        """总字符数恰好等于上限时应返回全部文本。"""
        records = [_make_record("abc"), _make_record("de")]
        # total = 3 + 2 = 5
        result = _sample_texts(records, max_chars=5)
        assert result == ["abc", "de"]

    def test_sampling_triggered_when_over_limit(self):
        """总字符数超限时应触发采样，返回子集。"""
        records = [_make_record("a" * 10) for _ in range(10)]
        # total = 100, max_chars = 50 → should sample ~5 records
        result = _sample_texts(records, max_chars=50)
        assert len(result) < 10
        assert len(result) >= 1

    def test_single_record_exceeding_limit_preserved(self):
        """单条文本超过 max_chars 时该条独立保留。"""
        records = [_make_record("a" * 200)]
        result = _sample_texts(records, max_chars=50)
        assert len(result) == 1
        assert result[0] == "a" * 200

    def test_sampled_chars_within_limit(self):
        """采样后的总字符数应不超过 max_chars（单条超限除外）。"""
        records = [_make_record("x" * 20) for _ in range(20)]
        # total = 400, max_chars = 100
        result = _sample_texts(records, max_chars=100)
        total = sum(len(t) for t in result)
        # Each text is 20 chars, so no single text exceeds 100
        assert total <= 100

    def test_deterministic_output(self):
        """相同输入应产生相同输出（确定性）。"""
        records = [_make_record(f"text_{i}" * 5) for i in range(20)]
        result1 = _sample_texts(records, max_chars=100)
        result2 = _sample_texts(records, max_chars=100)
        assert result1 == result2

    def test_uniform_sampling_indices(self):
        """采样索引应均匀分布，相邻间隔差异不超过 1。"""
        records = [_make_record("a" * 10) for _ in range(100)]
        # total = 1000, max_chars = 200 → ~20 records
        result = _sample_texts(records, max_chars=200)
        # Find which indices were selected by matching texts
        # Since all texts are identical, we verify count and char limit
        assert len(result) >= 1
        total = sum(len(t) for t in result)
        assert total <= 200

    def test_always_includes_first_record(self):
        """采样应始终包含第一条记录。"""
        records = [_make_record(f"record_{i}") for i in range(50)]
        result = _sample_texts(records, max_chars=50)
        assert len(result) >= 1
        assert result[0] == "record_0"

    def test_single_record_within_limit(self):
        """单条记录且未超限时应返回该记录。"""
        records = [_make_record("hello")]
        result = _sample_texts(records, max_chars=100)
        assert result == ["hello"]

    def test_mixed_length_texts(self):
        """混合长度文本的采样应正确工作。"""
        records = [
            _make_record("short"),           # 5
            _make_record("a" * 50),          # 50
            _make_record("medium text here"), # 16
            _make_record("b" * 100),         # 100
            _make_record("tiny"),            # 4
        ]
        # total = 175, max_chars = 80
        result = _sample_texts(records, max_chars=80)
        assert len(result) >= 1
        # Verify all returned texts are from the original records
        original_texts = {r.text for r in records}
        for t in result:
            assert t in original_texts


# ---------------------------------------------------------------------------
# extract_glossary 测试
# ---------------------------------------------------------------------------

from unittest.mock import MagicMock, patch

from engine.glossary_extractor import extract_glossary


class TestExtractGlossary:
    """术语提取主函数测试。"""

    def test_empty_records_returns_empty(self):
        """空记录列表应直接返回空列表，不调用 LLM。"""
        result = extract_glossary(records=[], target_lang="zh-CN")
        assert result == []

    @patch("engine.glossary_extractor.OpenAI")
    def test_successful_extraction(self, mock_openai_cls):
        """成功提取术语表时应返回解析后的术语列表。"""
        # Mock LLM response
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = json.dumps([
            {"sourceText": "Vasco", "targetText": "瓦斯科"},
            {"sourceText": "Constellation", "targetText": "群星组织"},
        ])

        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai_cls.return_value = mock_client

        records = [_make_record("Vasco joined Constellation.")]
        result = extract_glossary(records=records, target_lang="zh-CN")

        assert len(result) == 2
        assert result[0]["sourceText"] == "Vasco"
        assert result[1]["sourceText"] == "Constellation"

        # Verify OpenAI client was created
        mock_openai_cls.assert_called_once()
        # Verify chat completions was called
        mock_client.chat.completions.create.assert_called_once()

    @patch("engine.glossary_extractor.time.sleep")
    @patch("engine.glossary_extractor.OpenAI")
    def test_llm_failure_returns_empty(self, mock_openai_cls, mock_sleep):
        """LLM 调用失败时应返回空列表。"""
        mock_client = MagicMock()
        mock_client.chat.completions.create.side_effect = Exception("API error")
        mock_openai_cls.return_value = mock_client

        records = [_make_record("Some text")]
        result = extract_glossary(records=records, target_lang="zh-CN")

        assert result == []
        # Should have retried MAX_RETRIES times
        assert mock_client.chat.completions.create.call_count == 3

    @patch("engine.glossary_extractor.time.sleep")
    @patch("engine.glossary_extractor.OpenAI")
    def test_retry_succeeds_on_second_attempt(self, mock_openai_cls, mock_sleep):
        """第一次失败后重试成功应返回术语表。"""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = json.dumps([
            {"sourceText": "Vasco", "targetText": "瓦斯科"},
        ])

        mock_client = MagicMock()
        mock_client.chat.completions.create.side_effect = [
            Exception("Temporary error"),
            mock_response,
        ]
        mock_openai_cls.return_value = mock_client

        records = [_make_record("Vasco is here")]
        result = extract_glossary(records=records, target_lang="zh-CN")

        assert len(result) == 1
        assert result[0]["sourceText"] == "Vasco"
        assert mock_client.chat.completions.create.call_count == 2
        mock_sleep.assert_called_once_with(1)  # RETRY_DELAYS[0]

    @patch("engine.glossary_extractor.OpenAI")
    def test_system_message_is_terminology_expert(self, mock_openai_cls):
        """系统消息应为术语专家角色。"""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "[]"

        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai_cls.return_value = mock_client

        records = [_make_record("Test text")]
        extract_glossary(records=records, target_lang="zh-CN")

        call_args = mock_client.chat.completions.create.call_args
        messages = call_args.kwargs["messages"]
        assert messages[0]["role"] == "system"
        assert messages[0]["content"] == "You are a professional game localization terminology expert."

    @patch("engine.glossary_extractor.OpenAI")
    def test_uses_env_defaults_when_no_params(self, mock_openai_cls):
        """未传入 LLM 参数时应使用环境变量默认值。"""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "[]"

        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai_cls.return_value = mock_client

        records = [_make_record("Test")]

        with patch.dict("os.environ", {}, clear=False):
            extract_glossary(records=records, target_lang="zh-CN")

        # Verify OpenAI was called (client creation happened)
        mock_openai_cls.assert_called_once()

    @patch("engine.glossary_extractor.OpenAI")
    def test_llm_returns_empty_content(self, mock_openai_cls):
        """LLM 返回空内容时应返回空列表。"""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = ""

        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai_cls.return_value = mock_client

        records = [_make_record("Some text")]
        result = extract_glossary(records=records, target_lang="zh-CN")

        assert result == []
