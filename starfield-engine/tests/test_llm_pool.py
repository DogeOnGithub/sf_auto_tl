"""默认 LLM 凭证池单元测试。

<p>覆盖三块此前完全没有测试的行为：错误分类、成本分散调度、成员冷却与 failover。
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from engine.esm_parser import StringRecord
from engine.llm_client import _translate_batch
from engine.llm_config import MAX_RETRIES, POOL_MAX_MEMBER_SWITCHES
from engine.llm_pool import (
    ERROR_KIND_AUTH,
    ERROR_KIND_BAD_REQUEST,
    ERROR_KIND_MODEL_NOT_FOUND,
    ERROR_KIND_QUOTA,
    ERROR_KIND_RATE_LIMIT,
    ERROR_KIND_TRANSIENT,
    LlmPool,
    classify_error,
    normalize_base_url,
)
from tests.llm_test_helpers import (
    auth_error,
    bad_request_error,
    build_pool,
    connection_error,
    fixed_source,
    not_found_error,
    quota_error,
    rate_limit_error,
    raw_member,
    server_error,
)


def _records(n: int) -> list[StringRecord]:
    """生成 n 条测试用 StringRecord。"""
    return [
        StringRecord(record_id=f"NPC_:{i:08X}:FULL", text=f"Text {i}")
        for i in range(n)
    ]


def _completion(lines: list[str]) -> MagicMock:
    """构造一个 [编号] 译文 格式的补全响应。"""
    response = MagicMock()
    choice = MagicMock()
    choice.message.content = "\n".join(f"[{i + 1}] {line}" for i, line in enumerate(lines))
    choice.finish_reason = "stop"
    response.choices = [choice]
    response.usage = None
    return response


def _completion_with_usage(lines: list[str], prompt_tokens: int, completion_tokens: int) -> MagicMock:
    """构造一个带 token 用量的补全响应。"""
    response = _completion(lines)
    usage = MagicMock()
    usage.prompt_tokens = prompt_tokens
    usage.completion_tokens = completion_tokens
    usage.completion_tokens_details = None
    response.usage = usage
    return response


class TestClassifyError:
    """错误分类测试。

    分类是池化的地基：分错就意味着「该换成员的时候在原地重试」或者
    「该重试的时候把好成员判死」。
    """

    def test_rate_limit(self):
        """429 归为限流 冷却短。"""
        assert classify_error(rate_limit_error()) == ERROR_KIND_RATE_LIMIT

    def test_quota_exhausted_is_not_rate_limit(self):
        """配额耗尽同样是 429 但要人工充值 按限流的几十秒去等是白等。"""
        assert classify_error(quota_error()) == ERROR_KIND_QUOTA

    def test_auth(self):
        """401 归为鉴权失效。"""
        assert classify_error(auth_error()) == ERROR_KIND_AUTH

    def test_model_not_found(self):
        """404 归为模型不存在。"""
        assert classify_error(not_found_error()) == ERROR_KIND_MODEL_NOT_FOUND

    def test_bad_request(self):
        """400 归为请求级问题 不冷却成员。"""
        assert classify_error(bad_request_error()) == ERROR_KIND_BAD_REQUEST

    def test_server_error_is_transient(self):
        """5xx 归为瞬时错误 重试同一成员。"""
        assert classify_error(server_error()) == ERROR_KIND_TRANSIENT

    def test_connection_error_is_transient(self):
        """网络层错误归为瞬时错误。"""
        assert classify_error(connection_error()) == ERROR_KIND_TRANSIENT

    def test_unknown_defaults_to_transient(self):
        """无法识别的异常按瞬时处理 倾向重试而不是把成员判死。"""
        assert classify_error(Exception("something odd")) == ERROR_KIND_TRANSIENT


class TestNormalizeBaseUrl:
    """base_url 规整测试。

    线上出过 base_url 填成完整端点导致全部调用 404、却因失败批次静默回退原文
    而显示「翻译完成」的事故。
    """

    def test_strips_endpoint_suffix(self):
        """误填的 /chat/completions 后缀要去掉。"""
        assert normalize_base_url("https://api.x.com/v1/chat/completions") == "https://api.x.com/v1"

    def test_strips_trailing_slash(self):
        """末尾斜杠不影响 SDK 拼接 但统一去掉便于比对。"""
        assert normalize_base_url("https://api.x.com/v1/") == "https://api.x.com/v1"

    def test_none_passes_through(self):
        """空值原样返回。"""
        assert normalize_base_url(None) is None


class TestPoolRefresh:
    """池配置刷新与对账测试。"""

    def test_loads_members(self):
        """正常拉取后成员数与配置一致。"""
        pool = build_pool([raw_member(1), raw_member(2)])
        assert pool.size() == 2

    def test_skips_incomplete_member(self):
        """配置缺字段的成员跳过而不是让整次刷新失败。"""
        broken = raw_member(2)
        broken["apiKey"] = ""
        pool = LlmPool()
        with patch("engine.llm_pool.fetch_members", return_value=[raw_member(1), broken]):
            pool.refresh()
        assert pool.size() == 1

    def test_keeps_snapshot_when_fetch_fails(self):
        """拉取失败时沿用上一次快照 管理接口抖动不该让正在跑的任务失去凭证。"""
        pool = build_pool([raw_member(1)])
        with patch("engine.llm_pool.fetch_members", return_value=None):
            assert pool.refresh() == 1
        assert pool.size() == 1

    def test_drops_removed_member_state(self):
        """成员被删后连状态一起丢掉 不再出现在健康快照里。"""
        pool = build_pool([raw_member(1), raw_member(2)])
        with patch("engine.llm_pool.fetch_members", return_value=[raw_member(1)]):
            pool.refresh()
        assert [m["memberId"] for m in pool.health_snapshot()] == [1]

    def test_empty_pool_acquire_returns_none(self):
        """池为空时 acquire 给不出成员 调用方据此判失败。"""
        pool = LlmPool()
        with patch("engine.llm_pool.fetch_members", return_value=[]):
            pool.refresh()
        assert pool.acquire() is None


class TestPoolSelection:
    """成本分散调度测试。

    调度目标是分散成本而不是提速：任务内批次本来串行，多成员不会让单任务更快。
    所以排序依据是「窗口用量 / weight」而不是请求数轮询。
    """

    def test_picks_least_used(self):
        """窗口用量最少的成员优先。"""
        pool = build_pool([
            raw_member(1, window_tokens=5000),
            raw_member(2, window_tokens=100),
        ])
        assert pool.acquire().member_id == 2

    def test_weight_normalizes_usage(self):
        """weight 大的成员该承担更多 用量按配比归一化后再比。"""
        # 成员 1：1000/1 = 1000；成员 2：1500/5 = 300，尽管绝对用量更高仍该被选
        pool = build_pool([
            raw_member(1, weight=1, window_tokens=1000),
            raw_member(2, weight=5, window_tokens=1500),
        ])
        assert pool.acquire().member_id == 2

    def test_usage_accumulates_within_process(self):
        """本进程内产生的用量要立刻影响下一次选择 否则一个任务会全压在同一个成员上。"""
        pool = build_pool([raw_member(1), raw_member(2)])
        first = pool.acquire()
        pool.record_success(first, prompt_tokens=1000, completion_tokens=1000, reasoning_tokens=0)

        second = pool.acquire()
        assert second.member_id != first.member_id

    def test_skips_member_in_cooldown(self):
        """冷却中的成员不参与选择 即便它用量最少。"""
        pool = build_pool([
            raw_member(1, window_tokens=0),
            raw_member(2, window_tokens=9999),
        ])
        pool.record_failure(pool.acquire(exclude={2}), ERROR_KIND_AUTH, "invalid key")

        assert pool.acquire().member_id == 2

    def test_all_cooling_picks_shortest_remaining(self):
        """全部冷却时不放弃 挑剩余冷却最短的继续试。

        <p>冷却是暂时状态，而任务往往跑了一半，直接判失败要用户重传整个 mod。
        """
        pool = build_pool([raw_member(1), raw_member(2)])

        # 成员 1 给长冷却（鉴权 30 分钟），成员 2 给短冷却（限流 60 秒）
        pool.record_failure(pool.acquire(exclude={2}), ERROR_KIND_AUTH, "invalid key")
        pool.record_failure(pool.acquire(exclude={1}), ERROR_KIND_RATE_LIMIT, "slow down")

        assert pool.acquire().member_id == 2

    def test_success_clears_cooldown(self):
        """成功即解除冷却 继续压着只会让负载分布偏离配比。"""
        pool = build_pool([raw_member(1)])
        member = pool.acquire()
        pool.record_failure(member, ERROR_KIND_RATE_LIMIT, "slow down")
        assert pool.health_snapshot()[0]["available"] is False

        pool.record_success(member, 1, 1, 0)
        assert pool.health_snapshot()[0]["available"] is True

    def test_bad_request_does_not_cool_member(self):
        """400 是请求级问题不是成员的错 不该把成员冷却掉。"""
        pool = build_pool([raw_member(1)])
        member = pool.acquire()
        pool.record_failure(member, ERROR_KIND_BAD_REQUEST, "content filtered")

        assert pool.health_snapshot()[0]["available"] is True


class TestPoolStats:
    """用量归集与上报测试。"""

    def test_flush_reports_and_clears(self):
        """flush 把增量交出去并清空 重复 flush 不会重复上报。"""
        pool = build_pool([raw_member(1)])
        member = pool.acquire()
        pool.record_success(member, prompt_tokens=10, completion_tokens=20, reasoning_tokens=5)

        with patch("engine.llm_pool.report_stats") as mock_report:
            pool.flush()
            pool.flush()

        assert mock_report.call_count == 1
        items = mock_report.call_args[0][0]
        assert items[0]["memberId"] == 1
        assert items[0]["requests"] == 1
        assert items[0]["promptTokens"] == 10
        assert items[0]["completionTokens"] == 20
        assert items[0]["reasoningTokens"] == 5

    def test_failure_is_counted(self):
        """失败也要计数 否则疯狂 429 的成员在管理页上看着很干净。"""
        pool = build_pool([raw_member(1)])
        member = pool.acquire()
        pool.record_failure(member, ERROR_KIND_RATE_LIMIT, "slow down")

        with patch("engine.llm_pool.report_stats") as mock_report:
            pool.flush()

        items = mock_report.call_args[0][0]
        assert items[0]["requests"] == 1
        assert items[0]["failures"] == 1
        assert ERROR_KIND_RATE_LIMIT in items[0]["lastFailureReason"]

    def test_flush_merges_usage_into_baseline(self):
        """flush 清空增量的同时要把已用 token 并入基线。

        <p>不并入的话调度会把已经用掉的额度当成没用过，继续往同一个成员上压，
        成本分散在每次 flush 之后都会被重置一遍。
        """
        pool = build_pool([raw_member(1), raw_member(2)])
        member = pool.acquire()
        pool.record_success(member, prompt_tokens=5000, completion_tokens=0, reasoning_tokens=0)

        with patch("engine.llm_pool.report_stats"):
            pool.flush()

        assert pool.acquire().member_id != member.member_id

    def test_refresh_preserves_unflushed_delta(self):
        """刷新只换基线 未上报的增量要留着 它还没被算进 Java 给的那个数字里。"""
        pool = build_pool([raw_member(1, window_tokens=0)])
        member = pool.acquire()
        pool.record_success(member, prompt_tokens=100, completion_tokens=0, reasoning_tokens=0)

        with patch("engine.llm_pool.fetch_members", return_value=[raw_member(1, window_tokens=0)]):
            pool.refresh()

        with patch("engine.llm_pool.report_stats") as mock_report:
            pool.flush()
        assert mock_report.call_args[0][0][0]["promptTokens"] == 100


class TestPoolFailover:
    """批次级 failover 行为测试。"""

    def test_rate_limit_switches_member_without_sleeping(self):
        """限流是成员级问题 直接换人 不睡等。"""
        first, second = MagicMock(), MagicMock()
        first.chat.completions.create.side_effect = rate_limit_error()
        second.chat.completions.create.return_value = _completion(["译文0", "译文1"])
        pool = build_pool([raw_member(1), raw_member(2)], clients={1: first, 2: second})

        with patch("engine.llm_client.time.sleep") as mock_sleep:
            result = _translate_batch(pool, _records(2), "zh-CN", None, None)

        assert len(result) == 2
        assert first.chat.completions.create.call_count == 1
        assert second.chat.completions.create.call_count == 1
        mock_sleep.assert_not_called()

    def test_auth_failure_switches_member(self):
        """鉴权失效的成员不该被反复重试。"""
        first, second = MagicMock(), MagicMock()
        first.chat.completions.create.side_effect = auth_error()
        second.chat.completions.create.return_value = _completion(["译文0"])
        pool = build_pool([raw_member(1), raw_member(2)], clients={1: first, 2: second})

        result = _translate_batch(pool, _records(1), "zh-CN", None, None)

        assert len(result) == 1
        assert first.chat.completions.create.call_count == 1

    def test_transient_retries_same_member_before_switching(self):
        """瞬时错误先在同一成员上退避重试 重试耗尽才换人。"""
        first, second = MagicMock(), MagicMock()
        first.chat.completions.create.side_effect = server_error()
        second.chat.completions.create.return_value = _completion(["译文0"])
        pool = build_pool([raw_member(1), raw_member(2)], clients={1: first, 2: second})

        with patch("engine.llm_client.time.sleep") as mock_sleep:
            result = _translate_batch(pool, _records(1), "zh-CN", None, None)

        assert len(result) == 1
        assert first.chat.completions.create.call_count == MAX_RETRIES
        assert mock_sleep.call_count == MAX_RETRIES - 1

    def test_bad_request_switches_once_then_gives_up(self):
        """400 给一次换成员的机会来区分「成员模型名写错」和「内容被过滤」 之后不再试。"""
        first, second, third = MagicMock(), MagicMock(), MagicMock()
        for client in (first, second, third):
            client.chat.completions.create.side_effect = bad_request_error()
        pool = build_pool(
            [raw_member(1), raw_member(2), raw_member(3)],
            clients={1: first, 2: second, 3: third},
        )

        result = _translate_batch(pool, _records(1), "zh-CN", None, None)

        assert result == {}
        total_calls = sum(c.chat.completions.create.call_count for c in (first, second, third))
        assert total_calls == 2

    def test_total_attempts_are_capped(self):
        """切换与重试是两个维度 不封顶三成员池会把 3 次重试放大成 9 次付费请求。"""
        clients = {i: MagicMock() for i in (1, 2, 3)}
        for client in clients.values():
            client.chat.completions.create.side_effect = server_error()
        pool = build_pool([raw_member(1), raw_member(2), raw_member(3)], clients=clients)

        with patch("engine.llm_client.time.sleep"):
            result = _translate_batch(pool, _records(1), "zh-CN", None, None)

        assert result == {}
        total_calls = sum(c.chat.completions.create.call_count for c in clients.values())
        assert total_calls == MAX_RETRIES + POOL_MAX_MEMBER_SWITCHES

    def test_own_credentials_never_switch(self):
        """自带凭证没有成员可换 行为与池化改造前一致：非 400 退避重试三次。"""
        client = MagicMock()
        client.chat.completions.create.side_effect = rate_limit_error()

        with patch("engine.llm_client.time.sleep") as mock_sleep:
            result = _translate_batch(fixed_source(client), _records(1), "zh-CN", None, None)

        assert result == {}
        assert client.chat.completions.create.call_count == MAX_RETRIES
        assert mock_sleep.call_count == MAX_RETRIES - 1

    def test_usage_is_attributed_to_the_member_that_served(self):
        """用量要记在真正提供服务的成员上 否则成本分散的统计是错的。"""
        first, second = MagicMock(), MagicMock()
        first.chat.completions.create.side_effect = rate_limit_error()
        second.chat.completions.create.return_value = _completion_with_usage(["译文0"], 30, 70)
        pool = build_pool([raw_member(1), raw_member(2)], clients={1: first, 2: second})

        _translate_batch(pool, _records(1), "zh-CN", None, None)

        with patch("engine.llm_pool.report_stats") as mock_report:
            pool.flush()
        by_member = {item["memberId"]: item for item in mock_report.call_args[0][0]}
        assert by_member[1]["failures"] == 1
        assert by_member[1]["promptTokens"] == 0
        assert by_member[2]["failures"] == 0
        assert by_member[2]["promptTokens"] == 30
        assert by_member[2]["completionTokens"] == 70

    def test_split_retry_stays_on_same_member(self):
        """截断拆分要钉在同一成员上 换成员会让基于它输出上限做的拆分决策失效。"""
        truncated = _completion(["译文0"])
        truncated.choices[0].finish_reason = "length"
        first, second = MagicMock(), MagicMock()
        first.chat.completions.create.side_effect = [
            truncated,
            _completion(["译文0"]),
            _completion(["译文1"]),
        ]
        pool = build_pool([raw_member(1), raw_member(2)], clients={1: first, 2: second})

        result = _translate_batch(pool, _records(2), "zh-CN", None, None)

        assert len(result) == 2
        # 拆出来的两个子批次都打回成员 1
        assert first.chat.completions.create.call_count == 3
        assert second.chat.completions.create.call_count == 0


class TestHealthSnapshot:
    """健康快照测试，字段名需与 Java 侧 EnginePoolMemberHealth 对齐。"""

    def test_snapshot_shape(self):
        """快照包含管理页需要的全部字段 且不含任何凭证。"""
        pool = build_pool([raw_member(1)])
        member = pool.acquire()
        pool.record_failure(member, ERROR_KIND_AUTH, "invalid api key")

        snapshot = pool.health_snapshot()

        assert len(snapshot) == 1
        entry = snapshot[0]
        assert set(entry) == {
            "memberId", "available", "cooldownRemainingSeconds",
            "lastErrorKind", "lastErrorMessage",
        }
        assert entry["available"] is False
        assert entry["cooldownRemainingSeconds"] > 0
        assert entry["lastErrorKind"] == ERROR_KIND_AUTH
        assert "sk-" not in str(entry)
