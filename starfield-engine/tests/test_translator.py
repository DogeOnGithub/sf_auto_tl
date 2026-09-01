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
    record_count: int = 0,
    limit: int = 100000,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
    records: list[StringRecord] | None = None,
    cache_hits: dict[str, str] | None = None,
    ignore_already_translated: bool = False,
):
    """跑一个指定词条数和凭证组合的任务，返回终态与关键依赖 mock。

    Args:
        record_count: 待翻译词条数 传了 records 时忽略。
        limit: patch 后的词条护栏上限。
        llm_base_url: 调用方传入的 LLM 地址。
        llm_api_key: 调用方传入的 LLM Key。
        llm_model: 调用方传入的模型名。
        records: 自定义词条列表 用于构造特定文本内容的场景。
        cache_hits: query_cache 的返回值 用于构造缓存命中场景。
        ignore_already_translated: 是否忽略已汉化拦截（星裔放行开关）。

    Returns:
        (终态任务字典, 关键依赖的 mock 字典)。
    """
    records = records if records is not None else _make_records(record_count)

    def fake_translate(records=None, **_kwargs):
        """只为实际送进来的词条产出译文。

        <p>不能用固定的 return_value：已汉化词条会在送 LLM 之前被剔除，
        固定返回全量译文会掩盖「该跳过的词条其实被翻译了」这类回归。
        """
        return {r.record_id: "译文" for r in (records or [])}

    with patch("engine.translator.MAX_ENTRIES_WITHOUT_OWN_KEY", limit), \
            patch("engine.translator.parse_esm", return_value=records) as mock_parse, \
            patch("engine.translator.query_cache", return_value=cache_hits or {}) as mock_qc, \
            patch("engine.translator.save_cache"), \
            patch("engine.translator.write_esm",
                  return_value=WriteResult(backup_path="/tmp/b.esm", output_path="/tmp/o.esm")) as mock_write, \
            patch("engine.translator.translate_records",
                  side_effect=fake_translate) as mock_translate, \
            patch("engine.translator.extract_glossary", return_value=[]) as mock_extract:
        t = Translator()
        t.submit_task(
            "task-guard",
            "/tmp/test.esm",
            llm_base_url=llm_base_url,
            llm_api_key=llm_api_key,
            llm_model=llm_model,
            ignore_already_translated=ignore_already_translated,
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
            "write_esm": mock_write,
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


def _cn_records(n: int, start: int = 0) -> list[StringRecord]:
    """生成 n 条已是中文的 StringRecord。"""
    return [
        StringRecord(record_id=f"NPC_:{i:08X}:FULL", text=f"星际武器 {i}")
        for i in range(start, start + n)
    ]


def _placeholder_records(n: int, start: int = 0) -> list[StringRecord]:
    """生成 n 条整条都是 Bethesda 占位符的 StringRecord。"""
    return [
        StringRecord(record_id=f"TMLM:{i:08X}:ITXT", text=f"<Alias.CurrentName=Crew{i}><Token=CommentsOn{i}>")
        for i in range(start, start + n)
    ]


class TestAlreadyTranslatedGuard:
    """已汉化文件护栏测试。

    线上大量用户重新提交汉化过的 mod。这类文件送进 LLM 只会拿回一份和原文几乎一样的
    结果但 token 照价扣，而且 Java 侧写缓存时会丢弃「原文含中文」的条目，
    所以同一个文件提交 N 次就是 N 次全额付费、一次都不会命中缓存。
    """

    @staticmethod
    def _run(
        records: list[StringRecord],
        cache_hits: dict[str, str] | None = None,
        min_records: int = 30,
        ignore_already_translated: bool = False,
    ):
        """跑一个自定义词条内容的任务 并把占比护栏的样本量下限 patch 成可控值。"""
        with patch("engine.translator.ALREADY_TRANSLATED_MIN_RECORDS", min_records):
            return _run_guard_task(
                records=records,
                cache_hits=cache_hits,
                ignore_already_translated=ignore_already_translated,
            )

    def test_fully_chinese_file_is_rejected(self):
        """整个文件都是中文时判失败 错误信息要带上占比便于用户自查。"""
        task, mocks = self._run(_cn_records(40))

        assert task["status"] == STATUS_FAILED
        assert "简体中文" in task["error"]
        assert mocks["translate_records"].called is False

    def test_ratio_guard_makes_no_paid_call_and_skips_cache_query(self):
        """占比护栏卡在缓存查询之前 几十万词条的缓存查询本身就是一次大请求。"""
        _, mocks = self._run(_cn_records(40))

        assert mocks["query_cache"].called is False
        assert mocks["extract_glossary"].called is False
        assert mocks["translate_records"].called is False

    def test_english_file_is_not_blocked(self):
        """纯英文文件必须照常翻译 线上样本里这类文件占比稳定为 0。"""
        task, mocks = self._run(_make_records(40))

        assert task["status"] == STATUS_COMPLETED
        assert mocks["translate_records"].called is True

    def test_placeholder_records_do_not_dilute_ratio(self):
        """整条都是占位符的词条不进占比分母。

        <p>这是护栏能生效的前提：一个 100% 汉化的 mod 里可能有成百上千条
        <Alias.CurrentName=xxx> 这种永远是拉丁字母、也永远不需要翻译的词条，
        把它们算进分母的话占比会被压到 0.7 左右 阈值就永远拦不住。
        """
        records = _cn_records(40) + _placeholder_records(200, start=1000)

        task, mocks = self._run(records)

        assert task["status"] == STATUS_FAILED
        assert mocks["translate_records"].called is False

    def test_partially_translated_file_only_sends_english_to_llm(self):
        """半成品文件放行 但已是中文的词条不送 LLM。

        <p>占比 0.5 远低于阈值 属于「还有真实工作量」的文件；线上样本里有汉化了 60%
        就重新提交的 那 60% 送进 LLM 等于按原价买回一份原文。
        """
        english = _make_records(20)
        chinese = _cn_records(20, start=100)

        task, mocks = self._run(english + chinese)

        assert task["status"] == STATUS_COMPLETED
        sent = mocks["translate_records"].call_args.kwargs["records"]
        assert {r.record_id for r in sent} == {r.record_id for r in english}

    def test_pretranslated_records_keep_original_text_in_output(self):
        """被跳过的中文词条要以原文进入重组结果 否则重组会缺掉这批词条。"""
        english = _make_records(20)
        chinese = _cn_records(20, start=100)

        _, mocks = self._run(english + chinese)
        translations = mocks["write_esm"].call_args.kwargs["translations"]

        assert len(translations) == 40
        for r in chinese:
            assert translations[r.record_id] == r.text

    def test_small_fully_chinese_file_is_rejected_below_sample_floor(self):
        """样本量不够时占比护栏不生效 但「逐条过滤后无待翻词条」这条精确判定要兜住。"""
        task, mocks = self._run(_cn_records(5))

        assert task["status"] == STATUS_FAILED
        assert "简体中文" in task["error"]
        # 走的是第二道判定 所以缓存查询发生过、LLM 调用没有
        assert mocks["query_cache"].called is True
        assert mocks["translate_records"].called is False

    def test_full_cache_hit_is_not_mistaken_for_already_translated(self):
        """全部命中缓存是正常的零成本路径 不能被误判成已汉化。"""
        english = _make_records(40)
        cache_hits = {r.record_id: "译文" for r in english}

        task, mocks = self._run(english, cache_hits=cache_hits)

        assert task["status"] == STATUS_COMPLETED
        assert mocks["translate_records"].called is False


class TestIgnoreAlreadyTranslated:
    """已汉化拦截的放行开关测试。

    星裔（管理员）专用：只剩最后几条英文的文件中文占比必然过阈值会被拦死，
    而管理员恰恰是要把那几条补完。放行只跳过拦截动作，不改变逐条剔除已汉化词条的行为。
    """

    @staticmethod
    def _run(records: list[StringRecord], ignore: bool):
        """跑一个带放行开关的任务。"""
        return TestAlreadyTranslatedGuard._run(records, ignore_already_translated=ignore)

    def test_ignore_lets_last_english_records_through(self):
        """放行后只剩的那几条英文要真的被翻译。"""
        chinese = _cn_records(60)
        english = _make_records(1)

        task, mocks = self._run(chinese + english, ignore=True)

        assert task["status"] == STATUS_COMPLETED
        sent = mocks["translate_records"].call_args.kwargs["records"]
        assert {r.record_id for r in sent} == {english[0].record_id}

    def test_same_file_is_blocked_without_the_flag(self):
        """同一个文件不带开关时仍然被拦 证明放行确实来自开关而不是占比没到阈值。"""
        chinese = _cn_records(60)
        english = _make_records(1)

        task, mocks = self._run(chinese + english, ignore=False)

        assert task["status"] == STATUS_FAILED
        assert mocks["translate_records"].called is False

    def test_ignore_still_skips_chinese_records(self):
        """放行不等于重翻已汉化词条 否则等于把刚省下来的 token 又烧回去。"""
        chinese = _cn_records(60)
        english = _make_records(5)

        _, mocks = self._run(chinese + english, ignore=True)

        sent = mocks["translate_records"].call_args.kwargs["records"]
        assert {r.record_id for r in sent} == {r.record_id for r in english}

    def test_ignore_does_not_fabricate_work_when_nothing_is_left(self):
        """一条未汉化词条都没有时即便放行也判失败。

        <p>放行的用意是「补完剩下的英文」 而这里剩下 0 条；继续走下去只会产出一个和输入
        完全一样的文件 反而让管理员以为改动生效了。错误信息要说明是放行后仍无内容可翻。
        """
        task, mocks = self._run(_cn_records(60), ignore=True)

        assert task["status"] == STATUS_FAILED
        assert "忽略已汉化拦截" in task["error"]
        assert mocks["translate_records"].called is False

    def test_english_file_unaffected_by_the_flag(self):
        """纯英文文件本来就不受拦截影响 带开关也不该改变任何行为。"""
        task, mocks = self._run(_make_records(40), ignore=True)

        assert task["status"] == STATUS_COMPLETED
        assert mocks["translate_records"].called is True
