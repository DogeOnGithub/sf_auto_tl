"""LLM 客户端单元测试。"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from engine.esm_parser import StringRecord
from engine.llm_client import (
    MAX_RETRIES,
    RETRY_DELAYS,
    _parse_response,
    _translate_batch,
    translate_records,
)


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
    """构造一个模拟的 OpenAI ChatCompletion 响应。"""
    content = "\n".join(translated_lines)
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
        """返回行数与记录数一致时，逐行匹配。"""
        records = _make_records(3)
        response_text = "翻译0\n翻译1\n翻译2"
        result = _parse_response(response_text, records)

        assert len(result) == 3
        for i, r in enumerate(records):
            assert result[r.record_id] == f"翻译{i}"

    def test_fewer_lines_falls_back_to_original(self):
        """返回行数不足时，缺失的记录回退到原文。"""
        records = _make_records(3)
        response_text = "翻译0"
        result = _parse_response(response_text, records)

        assert result[records[0].record_id] == "翻译0"
        assert result[records[1].record_id] == records[1].text
        assert result[records[2].record_id] == records[2].text

    def test_empty_line_falls_back_to_original(self):
        """空翻译行回退到原文。"""
        records = _make_records(2)
        response_text = "翻译0\n"
        result = _parse_response(response_text, records)

        assert result[records[0].record_id] == "翻译0"
        assert result[records[1].record_id] == records[1].text

    def test_preserves_all_record_ids(self):
        """结果字典应包含所有输入记录的 ID。"""
        records = _make_records(5)
        response_text = "\n".join([f"T{i}" for i in range(5)])
        result = _parse_response(response_text, records)

        assert set(result.keys()) == {r.record_id for r in records}


# ---------------------------------------------------------------------------
# _translate_batch 测试（重试逻辑）
# ---------------------------------------------------------------------------

class TestTranslateBatch:
    """单批次翻译与重试逻辑测试。"""

    def test_success_on_first_attempt(self):
        """首次调用成功时直接返回结果。"""
        records = _make_records(2)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译0", "翻译1"])

        result = _translate_batch(client, "gpt-4o-mini", records, "zh-CN", None, None)

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

        result = _translate_batch(client, "gpt-4o-mini", records, "zh-CN", None, None)

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

        result = _translate_batch(client, "gpt-4o-mini", records, "zh-CN", None, None)

        assert result == {}
        assert client.chat.completions.create.call_count == MAX_RETRIES
        assert mock_sleep.call_count == MAX_RETRIES - 1

    def test_passes_custom_prompt_and_dictionary(self):
        """应将 custom_prompt 和 dictionary_entries 传递给 build_prompt。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])

        custom = "自定义翻译指令"
        entries = [{"sourceText": "Sword", "targetText": "剑"}]

        with patch("engine.llm_client.build_prompt") as mock_build:
            mock_build.return_value = "mocked prompt"
            _translate_batch(client, "gpt-4o-mini", records, "zh-CN", custom, entries)

            mock_build.assert_called_once_with(
                texts_to_translate=[records[0].text],
                custom_prompt=custom,
                dictionary_entries=entries,
            )

    def test_system_message_contains_target_lang(self):
        """system message 应包含目标语言。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])

        _translate_batch(client, "gpt-4o-mini", records, "ja-JP", None, None)

        call_args = client.chat.completions.create.call_args
        messages = call_args.kwargs.get("messages") or call_args[1].get("messages")
        system_msg = messages[0]["content"]
        assert "ja-JP" in system_msg


# ---------------------------------------------------------------------------
# translate_records 测试（批次分割与整体流程）
# ---------------------------------------------------------------------------

class TestTranslateRecords:
    """translate_records 整体流程测试。"""

    @patch("engine.llm_client._get_client")
    @patch("engine.llm_client._get_model", return_value="gpt-4o-mini")
    def test_empty_records_returns_empty(self, mock_model, mock_client):
        """空记录列表直接返回空字典。"""
        result = translate_records([])
        assert result == {}
        mock_client.assert_not_called()

    @patch("engine.llm_client._get_client")
    @patch("engine.llm_client._get_model", return_value="gpt-4o-mini")
    def test_single_batch(self, mock_model, mock_client):
        """记录数 <= batch_size 时只调用一次 LLM。"""
        records = _make_records(3)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(
            [f"翻译{i}" for i in range(3)]
        )
        mock_client.return_value = client

        result = translate_records(records, batch_size=10)

        assert len(result) == 3
        assert client.chat.completions.create.call_count == 1

    @patch("engine.llm_client._get_client")
    @patch("engine.llm_client._get_model", return_value="gpt-4o-mini")
    def test_multiple_batches(self, mock_model, mock_client):
        """记录数 > batch_size 时应分多批调用。"""
        records = _make_records(5)
        client = MagicMock()
        # 第一批 2 条，第二批 2 条，第三批 1 条
        client.chat.completions.create.side_effect = [
            _mock_completion(["翻译0", "翻译1"]),
            _mock_completion(["翻译2", "翻译3"]),
            _mock_completion(["翻译4"]),
        ]
        mock_client.return_value = client

        result = translate_records(records, batch_size=2)

        assert len(result) == 5
        assert client.chat.completions.create.call_count == 3
        # 验证所有记录 ID 都有翻译
        for r in records:
            assert r.record_id in result

    @patch("engine.llm_client._get_client")
    @patch("engine.llm_client._get_model", return_value="gpt-4o-mini")
    @patch("engine.llm_client.time.sleep")
    def test_partial_batch_failure(self, mock_sleep, mock_model, mock_client):
        """部分批次失败不影响其他批次。"""
        records = _make_records(4)
        client = MagicMock()
        # 第一批成功，第二批全部重试失败
        client.chat.completions.create.side_effect = [
            _mock_completion(["翻译0", "翻译1"]),
            Exception("fail"),
            Exception("fail"),
            Exception("fail"),
        ]
        mock_client.return_value = client

        result = translate_records(records, batch_size=2)

        # 第一批 2 条成功，第二批 0 条
        assert len(result) == 2
        assert records[0].record_id in result
        assert records[1].record_id in result

    @patch("engine.llm_client._get_client")
    @patch("engine.llm_client._get_model", return_value="gpt-4o-mini")
    def test_default_target_lang(self, mock_model, mock_client):
        """默认目标语言应为 zh-CN。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])
        mock_client.return_value = client

        translate_records(records)

        call_args = client.chat.completions.create.call_args
        messages = call_args.kwargs.get("messages") or call_args[1].get("messages")
        system_msg = messages[0]["content"]
        assert "zh-CN" in system_msg

    @patch("engine.llm_client._get_client")
    @patch("engine.llm_client._get_model", return_value="test-model")
    def test_uses_configured_model(self, mock_model, mock_client):
        """应使用环境变量配置的模型名称。"""
        records = _make_records(1)
        client = MagicMock()
        client.chat.completions.create.return_value = _mock_completion(["翻译"])
        mock_client.return_value = client

        translate_records(records)

        call_args = client.chat.completions.create.call_args
        assert call_args.kwargs.get("model") == "test-model" or call_args[1].get("model") == "test-model"
