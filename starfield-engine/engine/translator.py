"""翻译调度器，协调 ESM 解析、LLM 翻译、文件重组的完整流程。"""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, List, Optional

import requests

from engine.cache_client import query_cache, save_cache
from engine.esm_parser import parse_esm
from engine.esm_writer import write_esm
from engine.glossary_extractor import extract_glossary
from engine.llm_client import (
    DEFAULT_MAX_BATCH_CHARS,
    DEFAULT_MAX_BATCH_RECORDS,
    _split_batches,
    translate_records,
)
from engine.lang_detect import contains_chinese, measure_chinese_ratio
from engine.llm_config import (
    ALREADY_TRANSLATED_MIN_RECORDS,
    ALREADY_TRANSLATED_RATIO,
    MAX_ENTRIES_WITHOUT_OWN_KEY,
)
from engine.llm_pool import get_pool
from engine.strings_parser import parse_strings_dir
from engine.strings_writer import write_strings_dir

logger = logging.getLogger(__name__)

# 触发自动术语提取的最小批次数
# 只要需要分批 就存在同一专有名词在不同批次被译成不同名字的风险 这正是术语表要解决的问题；
# 单批次时 LLM 在一次调用里看到全部文本 译名天然一致 不需要额外花一次调用去提取。
# 阈值按「批次数」而不是「词条数」表达：批次上限调整时它自动跟着变，
# 用词条数写死过一次 结果批次缩小后一个 499 词条的 mod 被切成 7 批却仍然拿不到术语表。
GLOSSARY_MIN_BATCHES = 2

# 译文产出率低于此比例时告警 但不熔断
LOW_YIELD_WARN_RATE = 0.5

# 翻译来源类型
SOURCE_TYPE_ESM = "esm"
SOURCE_TYPE_STRINGS = "strings"

# 任务状态常量
STATUS_WAITING = "waiting"
STATUS_PARSING = "parsing"
STATUS_EXTRACTING_GLOSSARY = "extracting_glossary"
STATUS_TRANSLATING = "translating"
STATUS_ASSEMBLING = "assembling"
STATUS_COMPLETED = "completed"
STATUS_FAILED = "failed"

VALID_STATUSES = frozenset({
    STATUS_WAITING, STATUS_PARSING, STATUS_EXTRACTING_GLOSSARY,
    STATUS_TRANSLATING, STATUS_ASSEMBLING, STATUS_COMPLETED, STATUS_FAILED,
})


def _has_own_llm_credentials(
    llm_base_url: str | None,
    llm_api_key: str | None,
    llm_model: str | None,
) -> bool:
    """判断调用方是否自带了完整的 LLM 凭证。

    <p>判空用 truthy + strip 而不是 `is None`：空串在下游一样会被当成「没提供」而落回默认池。
    若这里用 `is None` 判定，传空串就能绕过词条上限继续烧公共额度。

    <p>要求地址、Key、模型名三项同时具备，口径必须和 llm_client._resolve_source 完全一致。
    只要有一项缺失，_resolve_source 就会回落到默认凭证池，那时花的是公共额度而不是用户自己的钱；
    如果这里只校验两项，调用方就能靠「填地址和 Key、不填模型名」绕过词条上限去消耗公共池。
    前端开启「用我的 KEY」时三项都是必填，所以这里主要防御绕过前端直连 API 的调用。

    Args:
        llm_base_url: 调用方传入的 LLM API 地址。
        llm_api_key: 调用方传入的 LLM API Key。
        llm_model: 调用方传入的模型名称。

    Returns:
        三者都非空返回 True。
    """
    has_base_url = bool(llm_base_url and llm_base_url.strip())
    has_api_key = bool(llm_api_key and llm_api_key.strip())
    has_model = bool(llm_model and llm_model.strip())
    return has_base_url and has_api_key and has_model


def _parse_source(source_type: str, file_path: str):
    """按来源类型解析待翻译文本。

    Args:
        source_type: 来源类型（esm 或 strings）。
        file_path: ESM 文件路径，或 Strings 目录路径。

    Returns:
        StringRecord 列表。
    """
    if source_type == SOURCE_TYPE_STRINGS:
        return parse_strings_dir(file_path)
    return parse_esm(file_path)


def _write_output(source_type: str, file_path: str, translations: Dict[str, str]) -> tuple[str, str | None]:
    """按来源类型回写翻译结果。

    Args:
        source_type: 来源类型（esm 或 strings）。
        file_path: ESM 文件路径，或 Strings 目录路径。
        translations: record_id -> 译文 的映射。

    Returns:
        (output_path, backup_path)。strings 模式下 output_path 为输出目录，backup_path 为 None。
    """
    if source_type == SOURCE_TYPE_STRINGS:
        output_dir = f"{file_path.rstrip('/')}_translated"
        result = write_strings_dir(
            original_dir=file_path,
            translations=translations,
            output_dir=output_dir,
        )
        return result.output_dir, None

    name, ext = file_path.rsplit(".", 1)
    output_path = f"{name}_translated.{ext}"
    backup_path = f"{name}_backup.{ext}"
    result = write_esm(
        original_path=file_path,
        translations=translations,
        output_path=output_path,
        backup_path=backup_path,
    )
    return result.output_path, result.backup_path


def merge_glossary_with_dictionary(
    glossary: list[dict],
    user_dictionary: list[dict] | None,
) -> list[dict]:
    """合并自动提取术语表与用户词典，用户词典优先。

    以 sourceText 为 key，先放入 glossary 条目，再用 user_dictionary 条目覆盖，
    确保用户的翻译偏好始终被尊重。

    Args:
        glossary: 自动提取的术语表。
        user_dictionary: 用户手动维护的词典。

    Returns:
        合并后的词典约束列表。
    """
    if not glossary and not user_dictionary:
        logger.debug("[merge_glossary_with_dictionary] 术语表和用户词典均为空 返回空列表")
        return []

    if not user_dictionary:
        logger.debug("[merge_glossary_with_dictionary] 用户词典为空 直接返回术语表 glossary_count %d", len(glossary))
        return glossary if glossary else []

    if not glossary:
        logger.debug("[merge_glossary_with_dictionary] 术语表为空 直接返回用户词典 user_dictionary_count %d", len(user_dictionary))
        return user_dictionary

    merged: dict[str, dict] = {}
    for entry in glossary:
        source = entry.get("sourceText")
        if source:
            merged[source] = entry

    for entry in user_dictionary:
        source = entry.get("sourceText")
        if source:
            merged[source] = entry

    result = list(merged.values())
    logger.info(
        "[merge_glossary_with_dictionary] 合并完成 glossary_count %d user_dictionary_count %d merged_count %d",
        len(glossary), len(user_dictionary), len(result),
    )
    return result


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
        skip_cache: bool = False,
        llm_base_url: str | None = None,
        llm_api_key: str | None = None,
        llm_model: str | None = None,
        enable_glossary_extraction: bool = True,
        source_type: str = SOURCE_TYPE_ESM,
        ignore_already_translated: bool = False,
    ) -> Dict[str, str]:
        """提交翻译任务并异步执行。

        Args:
            task_id: 任务唯一标识。
            file_path: ESM 文件路径，或 Strings 目录路径（strings 模式）。
            target_lang: 目标语言。
            custom_prompt: 用户自定义 Prompt。
            dictionary_entries: 词典词条列表。
            callback_url: 进度回调地址。
            skip_cache: 是否跳过缓存保存（confirmation 模式下为 True）。
            llm_base_url: 自定义 LLM API 地址。
            llm_api_key: 自定义 LLM API Key。
            llm_model: 自定义 LLM 模型名称。
            enable_glossary_extraction: 是否启用自动术语提取 默认 True。
            source_type: 来源类型（esm 或 strings）默认 esm。
            ignore_already_translated: 是否忽略「文件已汉化」的拦截 默认 False。
                星裔（管理员）专用：只剩最后几条英文的文件占比必然过阈值会被拦死，
                而管理员恰恰是要把那几条补完。只跳过拦截 不影响逐条剔除已汉化词条。

        Returns:
            包含 taskId 和 status 的响应字典。
        """
        logger.info("[submit_task] 提交翻译任务 task_id %s file_path %s skip_cache %s llm_model %s enable_glossary_extraction %s source_type %s ignore_already_translated %s", task_id, file_path, skip_cache, llm_model, enable_glossary_extraction, source_type, ignore_already_translated)

        with self._lock:
            self._tasks[task_id] = self._new_task(task_id, callback_url)

        thread = threading.Thread(
            target=self._run_task,
            args=(task_id, file_path, target_lang, custom_prompt, dictionary_entries, callback_url, skip_cache, llm_base_url, llm_api_key, llm_model, enable_glossary_extraction, source_type, ignore_already_translated),
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
        skip_cache: bool = False,
        llm_base_url: str | None = None,
        llm_api_key: str | None = None,
        llm_model: str | None = None,
        enable_glossary_extraction: bool = True,
        source_type: str = SOURCE_TYPE_ESM,
        ignore_already_translated: bool = False,
    ) -> None:
        """执行翻译任务的完整流程：凭证护栏 → 解析 → 词条数护栏 → 缓存查询 → 术语提取 → 翻译 → 缓存保存 → 重组。"""
        try:
            # 0.5 默认额度可用性护栏
            # 卡在解析之前：不依赖词条数就能判定，而文件最大 4GB，先解析再失败等于白等几分钟。
            # Java 侧在上传入口已经拦过一次，这里兜住绕过前端直连引擎的调用，
            # 以及上传成功之后管理员把成员全部停用的时序。
            # 只看配置层面有没有成员，成员全在冷却是暂时状态，交给 acquire 自己挑冷却最短的去试。
            if not _has_own_llm_credentials(llm_base_url, llm_api_key, llm_model):
                if get_pool().refresh() == 0:
                    logger.error("[_run_task] 默认凭证池为空 拒绝翻译 task_id %s", task_id)
                    self._set_error(
                        task_id,
                        "默认额度当前不可用 请在上传时打开「用我的 KEY」"
                        "并填写你自己的 API 地址、API Key 和模型名称后重新提交",
                    )
                    self._report_progress(task_id, callback_url)
                    return

            # 1. 解析 ESM 或 Strings 目录
            self._update_status(task_id, STATUS_PARSING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_task] 开始解析 task_id %s source_type %s", task_id, source_type)
            records = _parse_source(source_type, file_path)
            total = len(records)
            self._update_progress(task_id, 0, total)

            if total == 0:
                logger.info("[_run_task] 无可翻译记录 task_id %s", task_id)
                self._update_status(task_id, STATUS_COMPLETED)
                self._report_progress(task_id, callback_url)
                return

            # 1.4 已汉化文件护栏
            # 线上大量用户把汉化过的 mod 重新提交。这类文件送进 LLM 只会拿回一份和原文
            # 几乎一样的结果 但 token 照价扣；而且 Java 侧写缓存时会主动丢弃「原文含中文」
            # 的条目 所以同一个文件提交 N 次就是 N 次全额付费 一次都不会命中缓存。
            # 放在 1.5 之前：一个几十万词条的已汉化文件 报「已经是中文」比报「超出词条上限
            # 请自带 KEY」有用得多——后者会把用户引导去用自己的 KEY 烧一遍同样没意义的翻译。
            # 判定只读已解析的文本 不发任何请求 所以放在这里不增加成本。
            # ignore_already_translated 是星裔（管理员）的放行开关：一个只剩最后几条英文的
            # 文件占比必然过阈值 会被拦死 而管理员恰恰是要把那几条补完。放行只跳过「拦截」
            # 这个动作 不改变 2.1 的逐条剔除 所以补最后几条仍然只为那几条付费。
            detectable, chinese_count, chinese_ratio = measure_chinese_ratio(records)
            already_translated = detectable >= ALREADY_TRANSLATED_MIN_RECORDS and chinese_ratio >= ALREADY_TRANSLATED_RATIO
            if already_translated and ignore_already_translated:
                logger.warning(
                    "[_run_task] 文件已是中文 但调用方指定忽略拦截 继续翻译 task_id %s detectable %d chinese %d ratio %.4f",
                    task_id, detectable, chinese_count, chinese_ratio,
                )
            if already_translated and not ignore_already_translated:
                logger.warning(
                    "[_run_task] 文件已是中文 拒绝翻译 task_id %s detectable %d chinese %d ratio %.4f threshold %.4f",
                    task_id, detectable, chinese_count, chinese_ratio, ALREADY_TRANSLATED_RATIO,
                )
                self._set_error(
                    task_id,
                    f"这个文件已经是简体中文了（{detectable} 条可判定文本中有 {chinese_count} 条是中文 "
                    f"占比 {chinese_ratio:.1%}）无需再翻译。"
                    f"如果你要的是汉化前的原版文件 请重新下载 mod 原始版本后再上传",
                )
                self._report_progress(task_id, callback_url)
                return

            # 1.5 公共额度的词条数护栏
            # 必须卡在这里：往下第一个花钱的动作是术语提取（步骤 2.5），而它被 try/except
            # 包着降级，异常会被吞掉拦不住任务。放在解析拿到 total 之后、缓存查询之前，
            # 既保证零付费调用，也省掉一次几十万词条的缓存查询请求。
            # 池化之后被抽干的是整个池而不是单个 KEY，所以这个上限照旧生效、不随成员数放大。
            if total > MAX_ENTRIES_WITHOUT_OWN_KEY and not _has_own_llm_credentials(llm_base_url, llm_api_key, llm_model):
                logger.warning(
                    "[_run_task] 词条数超过公共额度上限且未自带凭证 拒绝翻译 task_id %s total %d limit %d",
                    task_id, total, MAX_ENTRIES_WITHOUT_OWN_KEY,
                )
                self._set_error(
                    task_id,
                    f"该文件共 {total} 条待翻译文本 超过公共额度上限 {MAX_ENTRIES_WITHOUT_OWN_KEY} 条。"
                    f"请在上传时打开「用我的 KEY」并填写你自己的 API 地址、API Key 和模型名称后重新提交",
                )
                self._report_progress(task_id, callback_url)
                return

            # 2. 查询缓存
            cached = query_cache(records, target_lang)
            cached_count = len(cached)
            logger.info("[_run_task] 缓存查询完成 task_id %s cached %d total %d", task_id, cached_count, total)

            # 过滤出未命中缓存的词条
            uncached_records = [r for r in records if r.record_id not in cached]

            # 2.1 逐条剔除已是中文的词条 直接沿用原文 不送 LLM
            # 1.4 的占比护栏只挡「整个文件都汉化过」 挡不住半成品：线上样本里有汉化了 60%
            # 就重新提交的文件 那 60% 送进 LLM 等于按原价买回一份原文。这里按条过滤 才能让
            # 半成品只为真正剩下的英文付费。同时它是 1.4 阈值敢取保守值的前提——阈值放过去的
            # 文件在这一步依然是零成本 所以宁可漏拦也不误拦。
            pretranslated = {}
            pending_records = []
            for r in uncached_records:
                if contains_chinese(r.text):
                    pretranslated[r.record_id] = r.text
                else:
                    pending_records.append(r)
            if pretranslated:
                logger.info(
                    "[_run_task] 跳过已是中文的词条 task_id %s skipped %d remaining %d",
                    task_id, len(pretranslated), len(pending_records),
                )
            uncached_records = pending_records

            # 已有最终译文的词条：缓存命中的用缓存译文 已是中文的用原文
            presolved = {**cached, **pretranslated}

            # 2.2 无待翻词条且没有任何缓存译文 说明整个文件的可翻译文本都已是中文
            # 与 1.4 的占比判定互补 且不依赖阈值和样本量：词条少的小文件走到这里被精确拦下。
            # 必须同时要求 cached 为空 否则会把「全部命中缓存」这条正常的零成本路径误判成已汉化。
            # 这道判定即便被放行也拦：忽略拦截的用意是「补完剩下的英文」 而这里剩下的英文是 0 条，
            # 放行下去只会产出一个和输入完全一样的文件 反而让管理员以为改动生效了。
            if not uncached_records and not cached and pretranslated:
                logger.warning(
                    "[_run_task] 全部可翻译词条均已是中文 拒绝翻译 task_id %s count %d ignore_already_translated %s",
                    task_id, len(pretranslated), ignore_already_translated,
                )
                hint = (
                    "已按要求忽略已汉化拦截 但逐条检查后没有任何未汉化的词条 没有可翻译的内容"
                    if ignore_already_translated
                    else "如果你要的是汉化前的原版文件 请重新下载 mod 原始版本后再上传"
                )
                self._set_error(
                    task_id,
                    f"这个文件的 {len(pretranslated)} 条可翻译文本全部已是简体中文 无需再翻译。{hint}",
                )
                self._report_progress(task_id, callback_url)
                return

            # 对 uncached 按缓存键 (record_type, subrecord_type, source_text) 去重
            # 相同文本只翻译一次，翻译完后映射回所有 record_id
            seen_keys: dict[tuple[str, str], str] = {}  # (sub_type, source_text) -> first record_id
            dedup_records = []
            dedup_map: dict[str, list[str]] = {}  # first_record_id -> [all_record_ids]
            for r in uncached_records:
                parts = r.record_id.rsplit(":", 1)
                sub_type = parts[-1] if len(parts) > 1 else ""
                # 去掉 #N 序号后缀用于去重匹配
                idx = sub_type.find("#")
                if idx >= 0:
                    sub_type = sub_type[:idx]
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

            # 缓存命中和已是中文的词条都不需要 LLM 计入已翻译进度
            presolved_count = len(presolved)
            self._update_progress(task_id, presolved_count, total)

            # 上报这些词条作为 items（供 confirmation 模式写入确认记录）
            # 已是中文的词条也必须上报 否则 confirmation 模式下确认记录数会少于总词条数
            # Java 侧 handleProgressCallback 会告警 且后续重组会缺掉这批词条
            if presolved:
                presolved_items = []
                records_by_id = {r.record_id: r for r in records}
                for rid, translated in presolved.items():
                    rec = records_by_id.get(rid)
                    if rec:
                        parts = rid.split(":", 2)
                        record_type = parts[0] if len(parts) > 0 else ""
                        presolved_items.append({
                            "recordId": rid,
                            "recordType": record_type,
                            "sourceText": rec.text,
                            "targetText": translated,
                            "editorId": rec.editor_id,
                        })
                if presolved_items:
                    self._report_progress(task_id, callback_url, items=presolved_items)

            # 2.5 术语提取（如启用且需要分批翻译）
            # 分批数必须和 translate_records 实际使用的上限一致 否则会出现
            # 「判断时认为单批、实际翻译时切成多批」这种拿不到术语表的窗口
            batch_count = len(_split_batches(
                dedup_records, DEFAULT_MAX_BATCH_CHARS, DEFAULT_MAX_BATCH_RECORDS
            ))
            needs_glossary = batch_count >= GLOSSARY_MIN_BATCHES
            if enable_glossary_extraction and uncached_records and needs_glossary:
                try:
                    self._update_status(task_id, STATUS_EXTRACTING_GLOSSARY)
                    self._report_progress(task_id, callback_url)
                    logger.info("[_run_task] 开始术语提取 task_id %s uncached_count %d", task_id, len(uncached_records))

                    glossary = extract_glossary(
                        records=uncached_records,
                        target_lang=target_lang,
                        llm_base_url=llm_base_url,
                        llm_api_key=llm_api_key,
                        llm_model=llm_model,
                    )
                    dictionary_entries = merge_glossary_with_dictionary(glossary, dictionary_entries)

                    logger.info("[_run_task] 术语提取完成 task_id %s glossary_count %d merged_count %d", task_id, len(glossary), len(dictionary_entries) if dictionary_entries else 0)
                except Exception as e:
                    logger.warning("[_run_task] 术语提取异常 降级为无术语约束模式 task_id %s error %s", task_id, str(e))
            elif enable_glossary_extraction and uncached_records and not needs_glossary:
                logger.info("[_run_task] 单批次翻译 LLM 可看到全部上下文 跳过术语提取 task_id %s dedup_count %d batch_count %d", task_id, len(dedup_records), batch_count)

            # 3. 翻译未命中词条
            self._update_status(task_id, STATUS_TRANSLATING)
            self._report_progress(task_id, callback_url)

            if uncached_records:
                logger.info("[_run_task] 开始翻译 task_id %s uncached_count %d dedup_count %d", task_id, len(uncached_records), len(dedup_records))

                def on_batch_done(translated_count: int) -> None:
                    """每批翻译完成后更新进度并上报（加上无需 LLM 的词条数）。"""
                    self._update_progress(task_id, presolved_count + translated_count, total)
                    self._report_progress(task_id, callback_url)

                def on_batch_translated(batch_result: dict, batch_records: list) -> None:
                    """每批翻译完成后立即保存缓存（除非 skip_cache），并上报 items 供 confirmation 模式使用。"""
                    if not skip_cache:
                        save_cache(batch_result, batch_records, target_lang, task_id)
                    # 构建 items 列表用于 confirmation 模式增量写入（包含去重展开的记录）
                    records_by_id = {r.record_id: r for r in uncached_records}
                    items = []
                    for rec in batch_records:
                        translated = batch_result.get(rec.record_id)
                        if translated:
                            # 展开 dedup_map 中所有重复的 record_id
                            for rid in dedup_map.get(rec.record_id, [rec.record_id]):
                                parts = rid.split(":", 2)
                                record_type = parts[0] if len(parts) > 0 else ""
                                source_rec = records_by_id.get(rid)
                                source_text = source_rec.text if source_rec else rec.text
                                editor_id = source_rec.editor_id if source_rec else rec.editor_id
                                items.append({
                                    "recordId": rid,
                                    "recordType": record_type,
                                    "sourceText": source_text,
                                    "targetText": translated,
                                    "editorId": editor_id,
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
                    llm_base_url=llm_base_url,
                    llm_api_key=llm_api_key,
                    llm_model=llm_model,
                    task_id=task_id,
                )

                # 3.5 零产出熔断
                # 一条译文都没拿到 说明是配置或账号层面的问题（base_url 写错、key 失效、
                # 模型名不存在），不是个别批次的偶发失败。继续往下走会把全部词条按原文
                # 回写成一个「翻译完成」的 ESM，用户拿到一个看起来正常实际没翻的文件，
                # 比直接失败更难排查。这里直接判失败。
                if not dedup_translations:
                    logger.error(
                        "[_run_task] 所有 LLM 调用均未产出译文 判定任务失败 task_id %s dedup_count %d",
                        task_id, len(dedup_records),
                    )
                    self._set_error(task_id, "所有 LLM 调用均未产出译文 请检查 LLM 地址、密钥和模型名配置")
                    self._report_progress(task_id, callback_url)
                    return

                # 将去重后的翻译结果展开回所有 record_id
                new_translations = {}
                for first_id, translated_text in dedup_translations.items():
                    for rid in dedup_map.get(first_id, [first_id]):
                        new_translations[rid] = translated_text

                # 产出率过低时告警 不熔断：部分批次失败仍有回退原文的价值
                yield_rate = len(dedup_translations) / len(dedup_records)
                if yield_rate < LOW_YIELD_WARN_RATE:
                    logger.warning(
                        "[_run_task] 译文产出率偏低 task_id %s translated %d dedup_count %d yield_rate %.2f",
                        task_id, len(dedup_translations), len(dedup_records), yield_rate,
                    )
            else:
                logger.info(
                    "[_run_task] 无需调用 LLM task_id %s cached %d pretranslated %d",
                    task_id, cached_count, len(pretranslated),
                )
                new_translations = {}

            # 5. 合并无需 LLM 的词条（缓存译文 + 已是中文的原文）和 LLM 结果
            translations = {**presolved, **new_translations}

            # 5.5 补全翻译失败的词条（用原文回退），确保每个可翻译词条都有对应结果
            missing_count = 0
            for r in records:
                if r.record_id not in translations:
                    translations[r.record_id] = r.text
                    missing_count += 1
            if missing_count > 0:
                logger.warning("[_run_task] 翻译失败词条用原文回退 task_id %s missing_count %d", task_id, missing_count)

            self._update_progress(task_id, len(translations), total)

            # 5.6 仅上报翻译失败回退的词条（缓存命中和 LLM 翻译的词条已在前面增量上报）
            if missing_count > 0:
                records_by_id = {r.record_id: r for r in records}
                fallback_items = []
                for r in records:
                    if r.record_id not in presolved and r.record_id not in new_translations:
                        parts = r.record_id.split(":", 2)
                        record_type = parts[0] if len(parts) > 0 else ""
                        fallback_items.append({
                            "recordId": r.record_id,
                            "recordType": record_type,
                            "sourceText": r.text,
                            "targetText": translations[r.record_id],
                            "editorId": r.editor_id,
                        })
                if fallback_items:
                    logger.info("[_run_task] 上报回退词条 task_id %s count %d", task_id, len(fallback_items))
                    self._report_progress(task_id, callback_url, items=fallback_items)

            # 6. 重组 ESM（confirmation 模式跳过，由后续 assembly 接口生成）
            if skip_cache:
                logger.info("[_run_task] confirmation 模式跳过重组 task_id %s", task_id)
                self._update_status(task_id, STATUS_COMPLETED)
                self._report_progress(task_id, callback_url)
                logger.info("[_run_task] 翻译任务完成 task_id %s", task_id)
            else:
                self._update_status(task_id, STATUS_ASSEMBLING)
                self._report_progress(task_id, callback_url)
                logger.info("[_run_task] 开始重组 task_id %s source_type %s", task_id, source_type)

                output_path, backup_path = _write_output(source_type, file_path, translations)

                with self._lock:
                    if task_id in self._tasks:
                        self._tasks[task_id]["status"] = STATUS_COMPLETED
                        self._tasks[task_id]["outputFilePath"] = output_path
                        self._tasks[task_id]["originalBackupPath"] = backup_path

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
        source_type: str = SOURCE_TYPE_ESM,
    ) -> Dict[str, str]:
        """提交组装任务（仅重组阶段，使用已确认的翻译结果）。

        Args:
            task_id: 任务唯一标识。
            file_path: 原始 ESM 文件路径，或 Strings 目录路径（strings 模式）。
            items: 已确认的翻译条目列表，每条包含 recordId 和 targetText。
            callback_url: 进度回调地址。
            source_type: 来源类型（esm 或 strings）默认 esm。

        Returns:
            包含 taskId 和 status 的响应字典。
        """
        logger.info("[submit_assembly] 提交组装任务 task_id %s file_path %s items_count %d source_type %s", task_id, file_path, len(items), source_type)

        with self._lock:
            self._tasks[task_id] = self._new_task(task_id, callback_url)

        thread = threading.Thread(
            target=self._run_assembly,
            args=(task_id, file_path, items, callback_url, source_type),
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
        source_type: str = SOURCE_TYPE_ESM,
    ) -> None:
        """执行组装任务：将已确认的翻译结果写回 ESM 文件或 Strings 目录。"""
        try:
            translations = {item["recordId"]: item["targetText"] for item in items}
            total = len(translations)
            self._update_progress(task_id, total, total)

            self._update_status(task_id, STATUS_ASSEMBLING)
            self._report_progress(task_id, callback_url)
            logger.info("[_run_assembly] 开始重组 task_id %s translations_count %d source_type %s", task_id, total, source_type)

            output_path, backup_path = _write_output(source_type, file_path, translations)

            with self._lock:
                if task_id in self._tasks:
                    self._tasks[task_id]["status"] = STATUS_COMPLETED
                    self._tasks[task_id]["outputFilePath"] = output_path
                    self._tasks[task_id]["originalBackupPath"] = backup_path

            self._report_progress(task_id, callback_url)
            logger.info("[_run_assembly] 组装任务完成 task_id %s", task_id)

        except Exception as e:
            logger.error("[_run_assembly] 组装任务异常 task_id %s error %s", task_id, str(e), exc_info=True)
            self._set_error(task_id, str(e))
            self._report_progress(task_id, callback_url)
