"""翻译调度器，协调 ESM 解析、LLM 翻译、文件重组的完整流程。"""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, List, Optional

import requests

from engine.esm_parser import parse_esm
from engine.esm_writer import write_esm
from engine.llm_client import translate_records

logger = logging.getLogger(__name__)

# 任务状态常量
STATUS_WAITING = "waiting"
STATUS_PARSING = "parsing"
STATUS_TRANSLATING = "translating"
STATUS_ASSEMBLING = "assembling"
STATUS_COMPLETED = "completed"
STATUS_FAILED = "failed"

VALID_STATUSES = frozenset({
    STATUS_WAITING, STATUS_PARSING, STATUS_TRANSLATING,
    STATUS_ASSEMBLING, STATUS_COMPLETED, STATUS_FAILED,
})


class Translator:
    """翻译调度器，管理翻译任务的生命周期并协调各组件。"""

    def __init__(self) -> None:
        self._tasks: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.Lock()

    def _new_task(self, task_id: str, callback_url: str | None = None) -> Dict[str, Any]:
        """创建新任务记录。"""
        return {
            "taskId": task_id,
            "status": STATUS_WAITING,
            "progress": {"translated": 0, "total": 0},
            "outputFilePath": None,
            "originalBackupPath": None,
            "error": None,
            "callbackUrl": callback_url,
        }

    def _update_status(self, task_id: str, status: str) -> None:
        """更新任务状态。"""
        with self._lock:
            if task_id in self._tasks:
                self._tasks[task_id]["status"] = status

    def _update_progress(self, task_id: str, translated: int, total: int) -> None:
        """更新翻译进度。"""
        with self._lock:
            if task_id in self._tasks:
                self._tasks[task_id]["progress"]["translated"] = translated
                self._tasks[task_id]["progress"]["total"] = total

    def _set_error(self, task_id: str, error: str) -> None:
        """设置任务错误信息并标记为失败。"""
        with self._lock:
            if task_id in self._tasks:
                self._tasks[task_id]["status"] = STATUS_FAILED
                self._tasks[task_id]["error"] = error

    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取任务状态信息。"""
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return None
            return dict(task, progress=dict(task["progress"]))

    def _report_progress(self, task_id: str, callback_url: str | None) -> None:
        """向 Backend 上报当前任务进度。"""
        if not callback_url:
            return
        task = self.get_task(task_id)
        if task is None:
            return
        try:
            requests.post(callback_url, json=task, timeout=30)
        except Exception as e:
            logger.warning("[_report_progress] 上报进度失败 task_id %s error %s", task_id, str(e))

    def submit_task(
        self,
        task_id: str,
        file_path: str,
        target_lang: str = "zh-CN",
        custom_prompt: Optional[str] = None,
        dictionary_entries: Optional[List[Dict]] = None,
        callback_url: str | None = None,
    ) -> Dict[str, str]:
        """提交翻译任务并异步执行。

        Args:
            task_id: 任务唯一标识。
            file_path: ESM 文件路径。
            target_lang: 目标语言。
            custom_prompt: 用户自定义 Prompt。
            dictionary_entries: 词典词条列表。
            callback_url: 进度回调地址。

        Returns:
            包含 taskId 和 status 的响应字典。
        """
        logger.info("[submit_task] 提交翻译任务 task_id %s file_path %s", task_id, file_path)

        with self._lock:
            self._tasks[task_id] = self._new_task(task_id, callback_url)

        thread = threading.Thread(
            target=self._run_task,
            args=(task_id, file_path, target_lang, custom_prompt, dictionary_entries, callback_url),
            daemon=True,
        )
        thread.start()

        return {"taskId": task_id, "status": "accepted"}

    def _run_task(
        self,
        task_id: str,
        file_path: str,
        target_lang: str,
        custom_prompt: Optional[str],
        dictionary_entries: Optional[List[Dict]],
        callback_url: str | None = None,
    ) -> None:
        """执行翻译任务的完整流程：解析 → 翻译 → 重组。"""
        try:
            # 1. 解析 ESM
            self._update_status(task_id, STATUS_PARSING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_task] 开始解析 task_id %s", task_id)
            records = parse_esm(file_path)
            total = len(records)
            self._update_progress(task_id, 0, total)

            if total == 0:
                logger.info("[_run_task] 无可翻译记录 task_id %s", task_id)
                self._update_status(task_id, STATUS_COMPLETED)
                self._report_progress(task_id, callback_url)
                return

            # 2. 翻译
            self._update_status(task_id, STATUS_TRANSLATING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_task] 开始翻译 task_id %s records_count %d", task_id, total)

            def on_batch_done(translated_count: int) -> None:
                """每批翻译完成后更新进度并上报。"""
                self._update_progress(task_id, translated_count, total)
                self._report_progress(task_id, callback_url)

            translations = translate_records(
                records=records,
                target_lang=target_lang,
                custom_prompt=custom_prompt,
                dictionary_entries=dictionary_entries,
                on_batch_done=on_batch_done,
            )
            self._update_progress(task_id, len(translations), total)

            # 3. 重组 ESM
            self._update_status(task_id, STATUS_ASSEMBLING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_task] 开始重组 task_id %s", task_id)

            output_path = file_path.rsplit(".", 1)[0] + "_translated.esm"
            backup_path = file_path.rsplit(".", 1)[0] + "_backup.esm"

            result = write_esm(
                original_path=file_path,
                translations=translations,
                output_path=output_path,
                backup_path=backup_path,
            )

            with self._lock:
                if task_id in self._tasks:
                    self._tasks[task_id]["status"] = STATUS_COMPLETED
                    self._tasks[task_id]["outputFilePath"] = result.output_path
                    self._tasks[task_id]["originalBackupPath"] = result.backup_path

            self._report_progress(task_id, callback_url)
            logger.info("[_run_task] 翻译任务完成 task_id %s", task_id)

        except Exception as e:
            logger.error("[_run_task] 翻译任务异常 task_id %s error %s", task_id, str(e), exc_info=True)
            self._set_error(task_id, str(e))
            self._report_progress(task_id, callback_url)
