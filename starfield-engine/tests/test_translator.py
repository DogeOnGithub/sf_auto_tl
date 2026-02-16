"""翻译调度器单元测试。"""

from __future__ import annotations

import time
from unittest.mock import MagicMock, patch

import pytest

from engine.esm_parser import StringRecord
from engine.esm_writer import WriteResult
from engine.translator import (
    STATUS_ASSEMBLING,
    STATUS_COMPLETED,
    STATUS_FAILED,
    STATUS_PARSING,
    STATUS_TRANSLATING,
    STATUS_WAITING,
    Translator,
)


def _make_records(n: int) -> list[StringRecord]:
    """生成 n 条测试用 StringRecord。"""
    return [
        StringRecord(record_id=f"NPC_:{i:08X}:FULL", text=f"Text {i}")
        for i in range(n)
    ]


class TestTranslatorTaskLifecycle:
    """任务生命周期与状态转换测试。"""

    def test_get_task_returns_none_for_unknown_id(self):
        """查询不存在的任务应返回 None。"""
        t = Translator()
        assert t.get_task("nonexistent") is None

    def test_submit_task_returns_accepted(self):
        """提交任务应返回 accepted 状态。"""
        t = Translator()
        with patch("engine.translator.parse_esm", return_value=[]):
            result = t.submit_task("task-1", "/tmp/test.esm")

        assert result == {"taskId": "task-1", "status": "accepted"}

    def test_task_initial_status_is_waiting_or_progressed(self):
        """提交后任务状态应为 waiting 或已开始处理。"""
        t = Translator()
        with patch("engine.translator.parse_esm", return_value=[]):
            t.submit_task("task-1", "/tmp/test.esm")

        task = t.get_task("task-1")
        assert task is not None
        assert task["status"] in {STATUS_WAITING, STATUS_PARSING, STATUS_COMPLETED}

    @patch("engine.translator.write_esm")
    @patch("engine.translator.translate_records")
    @patch("engine.translator.parse_esm")
    def test_completed_task_has_output_paths(self, mock_parse, mock_translate, mock_write):
        """完成的任务应包含输出文件路径和备份路径。"""
        records = _make_records(2)
        mock_parse.return_value = records
        mock_translate.return_value = {r.record_id: f"翻译{i}" for i, r in enumerate(records)}
        mock_write.return_value = WriteResult(backup_path="/tmp/backup.esm", output_path="/tmp/out.esm")

        t = Translator()
        t.submit_task("task-1", "/tmp/test.esm")

        # 等待异步任务完成
        for _ in range(50):
            task = t.get_task("task-1")
            if task and task["status"] == STATUS_COMPLETED:
                break
            time.sleep(0.05)

        task = t.get_task("task-1")
        assert task["status"] == STATUS_COMPLETED
        assert task["outputFilePath"] == "/tmp/out.esm"
        assert task["originalBackupPath"] == "/tmp/backup.esm"

    @patch("engine.translator.parse_esm")
    def test_failed_task_has_error_message(self, mock_parse):
        """失败的任务应包含错误信息。"""
        mock_parse.side_effect = Exception("parse error")

        t = Translator()
        t.submit_task("task-1", "/tmp/test.esm")

        for _ in range(50):
            task = t.get_task("task-1")
            if task and task["status"] == STATUS_FAILED:
                break
            time.sleep(0.05)

        task = t.get_task("task-1")
        assert task["status"] == STATUS_FAILED
        assert "parse error" in task["error"]

    @patch("engine.translator.parse_esm")
    def test_empty_records_completes_immediately(self, mock_parse):
        """无可翻译记录时任务应直接完成。"""
        mock_parse.return_value = []

        t = Translator()
        t.submit_task("task-1", "/tmp/test.esm")

        for _ in range(50):
            task = t.get_task("task-1")
            if task and task["status"] == STATUS_COMPLETED:
                break
            time.sleep(0.05)

        task = t.get_task("task-1")
        assert task["status"] == STATUS_COMPLETED
        assert task["progress"]["total"] == 0


class TestTranslatorProgress:
    """进度更新测试。"""

    @patch("engine.translator.write_esm")
    @patch("engine.translator.translate_records")
    @patch("engine.translator.parse_esm")
    def test_progress_reflects_translation_count(self, mock_parse, mock_translate, mock_write):
        """进度应反映已翻译记录数和总数。"""
        records = _make_records(5)
        mock_parse.return_value = records
        mock_translate.return_value = {r.record_id: f"翻译{i}" for i, r in enumerate(records)}
        mock_write.return_value = WriteResult(backup_path="/tmp/b.esm", output_path="/tmp/o.esm")

        t = Translator()
        t.submit_task("task-1", "/tmp/test.esm")

        for _ in range(50):
            task = t.get_task("task-1")
            if task and task["status"] == STATUS_COMPLETED:
                break
            time.sleep(0.05)

        task = t.get_task("task-1")
        assert task["progress"]["total"] == 5
        assert task["progress"]["translated"] == 5


class TestTranslatorParameterPassing:
    """参数传递测试。"""

    @patch("engine.translator.write_esm")
    @patch("engine.translator.translate_records")
    @patch("engine.translator.parse_esm")
    def test_passes_custom_prompt_and_dictionary(self, mock_parse, mock_translate, mock_write):
        """应将 customPrompt 和 dictionaryEntries 传递给 translate_records。"""
        records = _make_records(1)
        mock_parse.return_value = records
        mock_translate.return_value = {records[0].record_id: "翻译"}
        mock_write.return_value = WriteResult(backup_path="/tmp/b.esm", output_path="/tmp/o.esm")

        custom = "自定义指令"
        entries = [{"sourceText": "Sword", "targetText": "剑"}]

        t = Translator()
        t.submit_task("task-1", "/tmp/test.esm", target_lang="ja-JP", custom_prompt=custom, dictionary_entries=entries)

        for _ in range(50):
            task = t.get_task("task-1")
            if task and task["status"] == STATUS_COMPLETED:
                break
            time.sleep(0.05)

        mock_translate.assert_called_once()
        call_kwargs = mock_translate.call_args.kwargs
        assert call_kwargs["records"] == records
        assert call_kwargs["target_lang"] == "ja-JP"
        assert call_kwargs["custom_prompt"] == custom
        assert call_kwargs["dictionary_entries"] == entries
        assert callable(call_kwargs["on_batch_done"])
