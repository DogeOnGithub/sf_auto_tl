"""翻译调度器，协调 ESM 解析、LLM 翻译、文件重组的完整流程。"""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, List, Optional

import requests

from engine.cache_client import query_cache, save_cache
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

    def _report_progress(self, task_id: str, callback_url: str | None, items: list | None = None) -> None:
        """向 Backend 上报当前任务进度。

        Args:
            task_id: 任务 ID。
            callback_url: 回调地址。
            items: 本批次翻译结果条目列表（可选），用于 confirmation 模式增量写入。
        """
        if not callback_url:
            return
        task = self.get_task(task_id)
        if task is None:
            return
        payload = dict(task)
        if items:
            payload["items"] = items
        try:
            requests.post(callback_url, json=payload, timeout=30)
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
        """执行翻译任务的完整流程：解析 → 缓存查询 → 翻译 → 缓存保存 → 重组。"""
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

            # 2. 查询缓存
            cached = query_cache(records, target_lang)
            cached_count = len(cached)
            logger.info("[_run_task] 缓存查询完成 task_id %s cached %d total %d", task_id, cached_count, total)

            # 过滤出未命中缓存的词条
            uncached_records = [r for r in records if r.record_id not in cached]

            # 对 uncached 按缓存键 (record_type, subrecord_type, source_text) 去重
            # 相同文本只翻译一次，翻译完后映射回所有 record_id
            seen_keys: dict[tuple[str, str], str] = {}  # (sub_type, source_text) -> first record_id
            dedup_records = []
            dedup_map: dict[str, list[str]] = {}  # first_record_id -> [all_record_ids]
            for r in uncached_records:
                parts = r.record_id.rsplit(":", 1)
                sub_type = parts[-1] if len(parts) > 1 else ""
                key = (sub_type, r.text)
                if key not in seen_keys:
                    seen_keys[key] = r.record_id
                    dedup_records.append(r)
                    dedup_map[r.record_id] = [r.record_id]
                else:
                    dedup_map[seen_keys[key]].append(r.record_id)

            dedup_saved = len(uncached_records) - len(dedup_records)
            if dedup_saved > 0:
                logger.info("[_run_task] 去重节省 %d 条 LLM 调用 task_id %s", dedup_saved, task_id)

            # 缓存命中的词条计入已翻译进度
            self._update_progress(task_id, cached_count, total)

            # 上报缓存命中的词条作为 items（供 confirmation 模式写入确认记录）
            if cached:
                cached_items = []
                records_by_id = {r.record_id: r for r in records}
                for rid, translated in cached.items():
                    rec = records_by_id.get(rid)
                    if rec:
                        parts = rid.split(":", 2)
                        record_type = parts[0] if len(parts) > 0 else ""
                        cached_items.append({
                            "recordId": rid,
                            "recordType": record_type,
                            "sourceText": rec.text,
                            "targetText": translated,
                        })
                if cached_items:
                    self._report_progress(task_id, callback_url, items=cached_items)

            # 3. 翻译未命中词条
            self._update_status(task_id, STATUS_TRANSLATING)
            self._report_progress(task_id, callback_url)

            if uncached_records:
                logger.info("[_run_task] 开始翻译 task_id %s uncached_count %d dedup_count %d", task_id, len(uncached_records), len(dedup_records))

                def on_batch_done(translated_count: int) -> None:
                    """每批翻译完成后更新进度并上报（加上缓存命中数）。"""
                    self._update_progress(task_id, cached_count + translated_count, total)
                    self._report_progress(task_id, callback_url)

                def on_batch_translated(batch_result: dict, batch_records: list) -> None:
                    """每批翻译完成后立即保存缓存，并上报 items 供 confirmation 模式使用。"""
                    save_cache(batch_result, batch_records, target_lang, task_id)
                    # 构建 items 列表用于 confirmation 模式增量写入
                    items = []
                    for rec in batch_records:
                        translated = batch_result.get(rec.record_id)
                        if translated:
                            # record_id 格式: RECORD_TYPE:FORM_ID:SUBRECORD_TYPE
                            parts = rec.record_id.split(":", 2)
                            record_type = parts[0] if len(parts) > 0 else ""
                            items.append({
                                "recordId": rec.record_id,
                                "recordType": record_type,
                                "sourceText": rec.text,
                                "targetText": translated,
                            })
                    if items:
                        self._report_progress(task_id, callback_url, items=items)

                dedup_translations = translate_records(
                    records=dedup_records,
                    target_lang=target_lang,
                    custom_prompt=custom_prompt,
                    dictionary_entries=dictionary_entries,
                    on_batch_done=on_batch_done,
                    on_batch_translated=on_batch_translated,
                )

                # 将去重后的翻译结果展开回所有 record_id
                new_translations = {}
                for first_id, translated_text in dedup_translations.items():
                    for rid in dedup_map.get(first_id, [first_id]):
                        new_translations[rid] = translated_text
            else:
                logger.info("[_run_task] 所有词条命中缓存 task_id %s", task_id)
                new_translations = {}

            # 5. 合并缓存结果和 LLM 结果
            translations = {**cached, **new_translations}
            self._update_progress(task_id, len(translations), total)

            # 6. 重组 ESM
            self._update_status(task_id, STATUS_ASSEMBLING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_task] 开始重组 task_id %s", task_id)

            name, ext = file_path.rsplit(".", 1)
            output_path = f"{name}_translated.{ext}"
            backup_path = f"{name}_backup.{ext}"

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

    def submit_assembly(
        self,
        task_id: str,
        file_path: str,
        items: List[Dict],
        callback_url: str | None = None,
    ) -> Dict[str, str]:
        """提交组装任务（仅重组阶段，使用已确认的翻译结果）。

        Args:
            task_id: 任务唯一标识。
            file_path: 原始 ESM 文件路径。
            items: 已确认的翻译条目列表，每条包含 recordId 和 targetText。
            callback_url: 进度回调地址。

        Returns:
            包含 taskId 和 status 的响应字典。
        """
        logger.info("[submit_assembly] 提交组装任务 task_id %s file_path %s items_count %d", task_id, file_path, len(items))

        with self._lock:
            self._tasks[task_id] = self._new_task(task_id, callback_url)

        thread = threading.Thread(
            target=self._run_assembly,
            args=(task_id, file_path, items, callback_url),
            daemon=True,
        )
        thread.start()

        return {"taskId": task_id, "status": "accepted"}

    def _run_assembly(
        self,
        task_id: str,
        file_path: str,
        items: List[Dict],
        callback_url: str | None = None,
    ) -> None:
        """执行组装任务：将已确认的翻译结果写回 ESM 文件。"""
        try:
            translations = {item["recordId"]: item["targetText"] for item in items}
            total = len(translations)
            self._update_progress(task_id, total, total)

            self._update_status(task_id, STATUS_ASSEMBLING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_assembly] 开始重组 task_id %s translations_count %d", task_id, total)

            name, ext = file_path.rsplit(".", 1)
            output_path = f"{name}_translated.{ext}"
            backup_path = f"{name}_backup.{ext}"

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
            logger.info("[_run_assembly] 组装任务完成 task_id %s", task_id)

        except Exception as e:
            logger.error("[_run_assembly] 组装任务异常 task_id %s error %s", task_id, str(e), exc_info=True)
            self._set_error(task_id, str(e))
            self._report_progress(task_id, callback_url)
