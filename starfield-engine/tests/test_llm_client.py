"""LLM 客户端单元测试。"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from engine.esm_parser import StringRecord
from engine.llm_client import (
    MAX_PROMPT_DICT_ENTRIES,
    MAX_RETRIES,
    RETRY_DELAYS,
    _parse_response,
    _relevant_entries,
    _translate_batch,
    translate_records,
)
from tests.llm_test_helpers import fixed_source


# ---------------------------------------------------------------------------
# 辅助工具
# ---------------------------------------------------------------------------

def _make_records(n: int) -> list[StringRecord]:
    """生成 n 条测试用 StringRecord。"""
    return [
        StringRecord(record_id=f"NPC_:{i:08X}:FULL", text=f"Text {i}")
        for i in range(n)
    ]


def _mock_completion(translated_lines: list[str]) -> MagicMock:
    """构造一个模拟的 OpenAI ChatCompletion 响应。使用 [N] 编号格式。"""
    content = "\n".join(f"[{i+1}] {line}" for i, line in enumerate(translated_lines))
    message = MagicMock()
    message.content = content
    choice = MagicMock()
    choice.message = message
    response = MagicMock()
    response.choices = [choice]
    return response


# ---------------------------------------------------------------------------
# _parse_response 测试
# ---------------------------------------------------------------------------

class TestParseResponse:
    """翻译结果解析与 ID 匹配测试。"""

    def test_exact_match(self):
        """返回行数与记录数一致时，逐行匹配且无缺失。"""
        records = _make_records(3)
        response_text = "[1] 翻译0\n[2] 翻译1\n[3] 翻译2"
        result, missing = _parse_response(response_text, records)

        assert len(result) == 3
        assert missing == []
        for i, r in enumerate(records):
            assert result[r.record_id] == f"翻译{i}"

    def test_fewer_lines_reported_as_missing(self):
        """返回行数不足时，缺失的记录不进结果、只记入 missing（回退由上层处理）。"""
        records = _make_records(3)
        response_text = "[1] 翻译0"
        result, missing = _parse_response(response_text, records)

        assert result == {records[0].record_id: "翻译0"}
        assert missing == [records[1].record_id, records[2].record_id]

    def test_empty_line_reported_as_missing(self):
        """空翻译行记入 missing。"""
        records = _make_records(2)
        response_text = "[1] 翻译0"
        result, missing = _parse_response(response_text, records)

        assert result == {records[0].record_id: "翻译0"}
        assert missing == [records[1].record_id]

    def test_unnumbered_response_yields_no_translation(self):
        """响应缺少 [编号] 标记时全部记入 missing。"""
        records = _make_records(5)
        response_text = "\n".join([f"T{i}" for i in range(5)])
        result, missing = _parse_response(response_text, records)

        assert result == {}
        assert missing == [r.record_id for r in records]


# ---------------------------------------------------------------------------
# _translate_batch 测试（重试逻辑）
# ---------------------------------------------------------------------------

class TestTranslateBatch:
    """单批次翻译与重试逻辑测试。

    <p>这里统一用 FixedSource（自带凭证语义）：它不做 failover，所以断言的正是
    池化改造前那套「非 400 退避重试三次」的行为，用来兜住改造没有回归用户自带 KEY 的路径。
    """

    def test_success_on_first_attempt(self):
        """首次调用成功时直接返回结果。"""
        records = _make_records(2)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译0", "翻译1"])

        result = _translate_batch(fixed_source(client), records, "zh-CN", None, None)

        assert len(result) == 2
        assert result[records[0].record_id] == "翻译0"
        assert client.chat.completions.create.call_count == 1

    @patch("engine.llm_client.time.sleep")
    def test_retry_on_failure_then_success(self, mock_sleep):
        """前两次失败、第三次成功时应重试并返回结果。"""
        records = _make_records(2)
        client = MagicMock()
        client.chat.completions.create.side_effect = [
            Exception("timeout"),
            Exception("rate limit"),
            _mock_completion(["翻译0", "翻译1"]),
        ]

        result = _translate_batch(fixed_source(client), records, "zh-CN", None, None)

        assert len(result) == 2
        assert client.chat.completions.create.call_count == 3
        # 验证退避间隔
        assert mock_sleep.call_count == 2
        mock_sleep.assert_any_call(RETRY_DELAYS[0])
        mock_sleep.assert_any_call(RETRY_DELAYS[1])

    @patch("engine.llm_client.time.sleep")
    def test_all_retries_exhausted_returns_empty(self, mock_sleep):
        """3 次重试全部失败时返回空字典。"""
        records = _make_records(2)
        client = MagicMock()
        client.chat.completions.create.side_effect = Exception("persistent error")

        result = _translate_batch(fixed_source(client), records, "zh-CN", None, None)

        assert result == {}
        assert client.chat.completions.create.call_count == MAX_RETRIES
        assert mock_sleep.call_count == MAX_RETRIES - 1

    def test_passes_custom_prompt_and_dictionary(self):
        """应将 custom_prompt 和本批相关的 dictionary_entries 传递给 build_prompt。"""
        records = [StringRecord(record_id="NPC_:00000001:FULL", text="A Sword here")]
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])

        custom = "自定义翻译指令"
        entries = [
            {"sourceText": "Sword", "targetText": "剑"},
            # 本批文本里没出现 应被 _relevant_entries 过滤掉 不占 prompt 预算
            {"sourceText": "Spaceship", "targetText": "飞船"},
        ]

        with patch("engine.llm_client.build_prompt") as mock_build:
            mock_build.return_value = "mocked prompt"
            _translate_batch(fixed_source(client), records, "zh-CN", custom, entries)

            mock_build.assert_called_once_with(
                texts_to_translate=[records[0].text],
                custom_prompt=custom,
                dictionary_entries=[entries[0]],
            )

    def test_system_message_contains_target_lang(self):
        """system message 应包含目标语言。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])

        _translate_batch(fixed_source(client), records, "ja-JP", None, None)

        call_args = client.chat.completions.create.call_args
        messages = call_args.kwargs.get("messages") or call_args[1].get("messages")
        system_msg = messages[0]["content"]
        assert "ja-JP" in system_msg

    def test_uses_model_from_source(self):
        """模型名取自来源里的成员 而不是再去读环境变量。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])

        _translate_batch(fixed_source(client, "member-model"), records, "zh-CN", None, None)

        call_args = client.chat.completions.create.call_args
        assert call_args.kwargs.get("model") == "member-model"

    def test_no_credentials_returns_empty_without_calling_llm(self):
        """来源给不出成员时直接判本批失败 不发请求。"""
        records = _make_records(2)
        source = MagicMock()
        source.acquire.return_value = None

        result = _translate_batch(source, records, "zh-CN", None, None)

        assert result == {}
        source.client_for.assert_not_called()


# ---------------------------------------------------------------------------
# translate_records 测试（批次分割与整体流程）
# ---------------------------------------------------------------------------

class TestTranslateRecords:
    """translate_records 整体流程测试。"""

    @patch("engine.llm_client._resolve_source")
    def test_empty_records_returns_empty(self, mock_resolve):
        """空记录列表直接返回空字典 连凭证都不去解析。"""
        result = translate_records([])
        assert result == {}
        mock_resolve.assert_not_called()

    @patch("engine.llm_client._resolve_source")
    def test_single_batch(self, mock_resolve):
        """记录数 <= batch_size 时只调用一次 LLM。"""
        records = _make_records(3)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(
            [f"翻译{i}" for i in range(3)]
        )
        mock_resolve.return_value = fixed_source(client)

        result = translate_records(records, batch_size=10)

        assert len(result) == 3
        assert client.chat.completions.create.call_count == 1

    @patch("engine.llm_client._resolve_source")
    def test_multiple_batches(self, mock_resolve):
        """记录数 > max_batch_records 时应分多批调用。"""
        records = _make_records(5)
        client = MagicMock()
        # max_batch_records=2 强制分 3 批：2+2+1
        client.chat.completions.create.side_effect = [
            _mock_completion(["翻译0", "翻译1"]),
            _mock_completion(["翻译2", "翻译3"]),
            _mock_completion(["翻译4"]),
        ]
        mock_resolve.return_value = fixed_source(client)

        result = translate_records(records, max_batch_records=2)

        assert len(result) == 5
        assert client.chat.completions.create.call_count == 3
        for r in records:
            assert r.record_id in result

    @patch("engine.llm_client._resolve_source")
    @patch("engine.llm_client.time.sleep")
    def test_partial_batch_failure(self, mock_sleep, mock_resolve):
        """部分批次失败不影响其他批次。"""
        records = _make_records(4)
        client = MagicMock()
        # max_batch_records=2 强制分 2 批 第一批成功 第二批全部重试失败
        client.chat.completions.create.side_effect = [
            _mock_completion(["翻译0", "翻译1"]),
            Exception("fail"),
            Exception("fail"),
            Exception("fail"),
        ]
        mock_resolve.return_value = fixed_source(client)

        result = translate_records(records, max_batch_records=2)

        assert len(result) == 2
        assert records[0].record_id in result
        assert records[1].record_id in result

    @patch("engine.llm_client._resolve_source")
    def test_default_target_lang(self, mock_resolve):
        """默认目标语言应为 zh-CN。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])
        mock_resolve.return_value = fixed_source(client)

        translate_records(records)

        call_args = client.chat.completions.create.call_args
        messages = call_args.kwargs.get("messages") or call_args[1].get("messages")
        system_msg = messages[0]["content"]
        assert "zh-CN" in system_msg

    @patch("engine.llm_client._resolve_source")
    def test_uses_model_from_resolved_source(self, mock_resolve):
        """应使用来源里成员携带的模型名称。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])
        mock_resolve.return_value = fixed_source(client, "test-model")

        translate_records(records)

        call_args = client.chat.completions.create.call_args
        assert call_args.kwargs.get("model") == "test-model"

    @patch("engine.llm_client._resolve_source", return_value=None)
    def test_no_credentials_returns_empty(self, mock_resolve):
        """无可用凭证时直接返回空 让上层的零产出熔断把任务判失败。"""
        result = translate_records(_make_records(2))
        assert result == {}

    @patch("engine.llm_client._resolve_source")
    def test_flushes_usage_even_when_batch_raises(self, mock_resolve):
        """任务中途异常也要把已产生的成员用量交出去 否则这部分成本在分散度上不可见。"""
        records = _make_records(2)
        source = MagicMock()
        source.acquire.side_effect = RuntimeError("boom")
        mock_resolve.return_value = source

        with pytest.raises(RuntimeError):
            translate_records(records)

        source.flush.assert_called_once()


# ---------------------------------------------------------------------------
# _relevant_entries 测试（词典按批过滤）
# ---------------------------------------------------------------------------

class TestRelevantEntries:
    """词典按批过滤测试。"""

    def test_filters_out_absent_terms(self):
        """没出现在本批文本里的术语对这批没有约束价值 应被过滤掉。"""
        entries = [
            {"sourceText": "Vasco", "targetText": "瓦斯科"},
            {"sourceText": "Spaceship", "targetText": "飞船"},
        ]
        result = _relevant_entries(entries, ["Vasco is here"])

        assert result == [entries[0]]

    def test_match_is_case_insensitive(self):
        """术语表由 LLM 生成 可能把大小写归一化 精确匹配会漏掉约束。"""
        entries = [{"sourceText": "Weaponengineering Rank", "targetText": "武器工程等级"}]
        result = _relevant_entries(entries, ["Increases WeaponEngineering Rank by 1"])

        assert result == entries

    def test_keeps_original_casing_in_output(self):
        """只有命中判断忽略大小写 下发给 prompt 的仍是词典里原本的写法。"""
        entries = [{"sourceText": "VASCO", "targetText": "瓦斯科"}]
        result = _relevant_entries(entries, ["vasco follows you"])

        assert result[0]["sourceText"] == "VASCO"

    def test_truncates_to_cap_keeping_longest(self):
        """条数超上限时保留较长的术语 长术语更容易被误译。"""
        entries = [
            {"sourceText": "T" * (i + 1), "targetText": f"译{i}"}
            for i in range(MAX_PROMPT_DICT_ENTRIES + 5)
        ]
        result = _relevant_entries(entries, ["T" * (MAX_PROMPT_DICT_ENTRIES + 5)])

        assert len(result) == MAX_PROMPT_DICT_ENTRIES
        assert len(result[0]["sourceText"]) > len(result[-1]["sourceText"])

    def test_empty_dictionary_passes_through(self):
        """空词典原样返回 不构造多余对象。"""
        assert _relevant_entries(None, ["text"]) is None
        assert _relevant_entries([], ["text"]) == []
