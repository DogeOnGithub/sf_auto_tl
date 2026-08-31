"""翻译调度器单元测试。"""

from __future__ import annotations

import time
from unittest.mock import MagicMock, patch

import pytest

from engine.esm_parser import StringRecord
from engine.esm_writer import WriteResult
from engine.llm_client import DEFAULT_MAX_BATCH_RECORDS
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


@pytest.fixture(autouse=True)
def available_pool():
    """默认让默认凭证池有可用成员。

    <p>池空护栏卡在 _run_task 最前面，不 stub 的话每个用例都会先去打内网请求、
    拿不到成员就直接判失败，而这些用例测的是解析、进度、参数传递这些与凭证无关的逻辑。
    池空本身的行为由 TestDefaultQuotaGuard 单独覆盖。

    Yields:
        被 patch 进去的池 mock，用例可改 refresh 返回值来模拟池空。
    """
    pool = MagicMock()
    pool.refresh.return_value = 1
    with patch("engine.translator.get_pool", return_value=pool):
        yield pool


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

    @patch("engine.translator.save_cache")
    @patch("engine.translator.query_cache", return_value={})
    @patch("engine.translator.write_esm")
    @patch("engine.translator.translate_records")
    @patch("engine.translator.parse_esm")
    @patch("engine.translator.extract_glossary", return_value=[])
    def test_completed_task_has_output_paths(self, mock_extract, mock_parse, mock_translate, mock_write, mock_qc, mock_sc):
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

    @patch("engine.translator.save_cache")
    @patch("engine.translator.query_cache", return_value={})
    @patch("engine.translator.write_esm")
    @patch("engine.translator.translate_records")
    @patch("engine.translator.parse_esm")
    @patch("engine.translator.extract_glossary", return_value=[])
    def test_progress_reflects_translation_count(self, mock_extract, mock_parse, mock_translate, mock_write, mock_qc, mock_sc):
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

    @patch("engine.translator.save_cache")
    @patch("engine.translator.query_cache", return_value={})
    @patch("engine.translator.write_esm")
    @patch("engine.translator.translate_records")
    @patch("engine.translator.parse_esm")
    @patch("engine.translator.extract_glossary", return_value=[])
    def test_passes_custom_prompt_and_dictionary(self, mock_extract, mock_parse, mock_translate, mock_write, mock_qc, mock_sc):
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


class TestGlossaryTrigger:
    """术语提取触发条件测试。

    阈值按批次数而不是词条数表达：只要需要分批就存在同一专有名词在不同批次
    被译成不同名字的风险。用词条数写死过一次，结果批次上限调小后，一个 499 词条
    的 mod 被切成 7 批却仍然拿不到术语表。
    """

    @staticmethod
    def _run(record_count: int):
        """跑一个指定词条数的任务 返回 extract_glossary 的 mock。

        Args:
            record_count: 待翻译词条数。

        Returns:
            extract_glossary 的 mock 对象 可用 called 判断是否触发提取。
        """
        records = _make_records(record_count)
        with patch("engine.translator.parse_esm", return_value=records), \
                patch("engine.translator.query_cache", return_value={}), \
                patch("engine.translator.save_cache"), \
                patch("engine.translator.write_esm",
                      return_value=WriteResult(backup_path="/tmp/b.esm", output_path="/tmp/o.esm")), \
                patch("engine.translator.translate_records",
                      return_value={r.record_id: "译文" for r in records}), \
                patch("engine.translator.extract_glossary", return_value=[]) as mock_extract:
            t = Translator()
            t.submit_task("task-glossary", "/tmp/test.esm")
            for _ in range(100):
                task = t.get_task("task-glossary")
                if task and task["status"] in {STATUS_COMPLETED, STATUS_FAILED}:
                    break
                time.sleep(0.02)
            assert t.get_task("task-glossary")["status"] == STATUS_COMPLETED
            return mock_extract

    def test_single_batch_skips_extraction(self):
        """单批次时 LLM 一次看到全部文本 译名天然一致 不需要额外花一次调用提取。"""
        assert self._run(DEFAULT_MAX_BATCH_RECORDS).called is False

    def test_multi_batch_triggers_extraction(self):
        """超出单批上限一条就会分批 此时必须提取术语表兜住跨批译名一致性。"""
        assert self._run(DEFAULT_MAX_BATCH_RECORDS + 1).called is True


def _run_guard_task(
    record_count: int,
    limit: int = 100000,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
):
    """跑一个指定词条数和凭证组合的任务，返回终态与关键依赖 mock。

    Args:
        record_count: 待翻译词条数。
        limit: patch 后的词条护栏上限。
        llm_base_url: 调用方传入的 LLM 地址。
        llm_api_key: 调用方传入的 LLM Key。
        llm_model: 调用方传入的模型名。

    Returns:
        (终态任务字典, 关键依赖的 mock 字典)。
    """
    records = _make_records(record_count)
    with patch("engine.translator.MAX_ENTRIES_WITHOUT_OWN_KEY", limit), \
            patch("engine.translator.parse_esm", return_value=records) as mock_parse, \
            patch("engine.translator.query_cache", return_value={}) as mock_qc, \
            patch("engine.translator.save_cache"), \
            patch("engine.translator.write_esm",
                  return_value=WriteResult(backup_path="/tmp/b.esm", output_path="/tmp/o.esm")), \
            patch("engine.translator.translate_records",
                  return_value={r.record_id: "译文" for r in records}) as mock_translate, \
            patch("engine.translator.extract_glossary", return_value=[]) as mock_extract:
        t = Translator()
        t.submit_task(
            "task-guard",
            "/tmp/test.esm",
            llm_base_url=llm_base_url,
            llm_api_key=llm_api_key,
            llm_model=llm_model,
        )
        for _ in range(200):
            task = t.get_task("task-guard")
            if task and task["status"] in {STATUS_COMPLETED, STATUS_FAILED}:
                break
            time.sleep(0.02)
        return t.get_task("task-guard"), {
            "parse_esm": mock_parse,
            "query_cache": mock_qc,
            "translate_records": mock_translate,
            "extract_glossary": mock_extract,
        }


class TestDefaultQuotaGuard:
    """默认凭证池可用性护栏测试。

    池为空时直接拒绝任务而不是回退到某个内置 KEY——回退会让「配置漏了」
    表现成「悄悄花了别的钱」。护栏卡在解析之前，文件最大 4GB，先解析再失败等于白等。
    """

    def test_empty_pool_fails_task_before_parsing(self, available_pool):
        """未自带凭证且池为空时判失败 且连解析都不做。"""
        available_pool.refresh.return_value = 0

        task, mocks = _run_guard_task(record_count=3)

        assert task["status"] == STATUS_FAILED
        assert "用我的 KEY" in task["error"]
        assert mocks["parse_esm"].called is False
        assert mocks["translate_records"].called is False

    def test_empty_pool_does_not_block_own_credentials(self, available_pool):
        """自带完整凭证时不看池 池空也照样翻。"""
        available_pool.refresh.return_value = 0

        task, mocks = _run_guard_task(
            record_count=3,
            llm_base_url="https://my.api.com",
            llm_api_key="sk-mine",
            llm_model="my-model",
        )

        assert task["status"] == STATUS_COMPLETED
        assert mocks["translate_records"].called is True
        # 自带凭证根本不该去碰池
        available_pool.refresh.assert_not_called()


class TestEntryLimitGuard:
    """公共额度的词条数护栏测试。

    护栏只在「超过上限」且「未自带完整凭证」时生效。用例统一把上限 patch 成个位数，
    避免为了跨过 10 万这条线去构造 10 万条记录。
    """

    @staticmethod
    def _run(
        record_count: int,
        limit: int,
        llm_base_url: str | None = None,
        llm_api_key: str | None = None,
        llm_model: str | None = None,
    ):
        """转调共用的任务执行辅助。"""
        return _run_guard_task(
            record_count=record_count,
            limit=limit,
            llm_base_url=llm_base_url,
            llm_api_key=llm_api_key,
            llm_model=llm_model,
        )

    def test_over_limit_without_own_key_fails(self):
        """超过上限又没自带凭证时应判失败 且错误信息带上实际词条数和上限便于用户自查。"""
        task, _ = self._run(record_count=6, limit=5)

        assert task["status"] == STATUS_FAILED
        assert "6" in task["error"]
        assert "5" in task["error"]

    def test_over_limit_without_own_key_makes_no_paid_call(self):
        """护栏的意义在于零付费调用 术语提取和翻译都不能被触达。

        query_cache 也一并断言：几十万词条的缓存查询本身就是一次大请求，
        护栏放在它之前才算真正卡在最前面。
        """
        _, mocks = self._run(record_count=6, limit=5)

        assert mocks["query_cache"].called is False
        assert mocks["extract_glossary"].called is False
        assert mocks["translate_records"].called is False

    def test_over_limit_with_own_credentials_passes(self):
        """自带地址、KEY 和模型名时花的是用户自己的钱 不受上限约束。"""
        task, mocks = self._run(
            record_count=6, limit=5,
            llm_base_url="https://my.api.com", llm_api_key="sk-mine", llm_model="my-model",
        )

        assert task["status"] == STATUS_COMPLETED
        assert mocks["translate_records"].called is True

    def test_within_limit_without_own_key_passes(self):
        """正好等于上限时放行 护栏用的是严格大于。"""
        task, mocks = self._run(record_count=5, limit=5)

        assert task["status"] == STATUS_COMPLETED
        assert mocks["translate_records"].called is True

    def test_blank_key_treated_as_missing(self):
        """空白 KEY 在 _resolve_source 里会落回默认池 所以必须等同于没提供。"""
        task, _ = self._run(
            record_count=6, limit=5,
            llm_base_url="https://my.api.com", llm_api_key="   ", llm_model="my-model",
        )

        assert task["status"] == STATUS_FAILED

    def test_key_without_base_url_treated_as_missing(self):
        """只填 KEY 不填地址会落回默认池 不算完全自费。"""
        task, _ = self._run(record_count=6, limit=5, llm_api_key="sk-mine", llm_model="my-model")

        assert task["status"] == STATUS_FAILED

    def test_credentials_without_model_treated_as_missing(self):
        """只填地址和 KEY、不填模型名同样会落回默认池 花的是公共额度。

        <p>护栏口径必须和 _resolve_source 完全一致，否则「填地址和 KEY、不填模型名」
        就是一条绕过词条上限去消耗公共池的路径。
        """
        task, mocks = self._run(
            record_count=6, limit=5,
            llm_base_url="https://my.api.com", llm_api_key="sk-mine",
        )

        assert task["status"] == STATUS_FAILED
        assert mocks["translate_records"].called is False
