"""默认 LLM 凭证池：成员选择、错误分类、健康度与用量归集。

<p>池只服务「走默认额度」的任务。用户自带 KEY 时凭证从请求直达 FixedSource，
不参与选择、不 failover、不计入统计——那是用户自己的钱。

<p>调度目标是分散成本而不是提速：任务内批次本来就是串行的，多成员并不会让单个任务更快。
所以选择依据是「窗口内已消耗 token / weight 最小优先」，而不是按请求数轮询——
批次大小差异很大（词条 p50 73 字符、p90 214 字符），请求数均分并不等于花钱均分。
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set

from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    OpenAI,
    PermissionDeniedError,
    RateLimitError,
)

from engine.llm_config import (
    POOL_COOLDOWN_AUTH,
    POOL_COOLDOWN_MODEL_NOT_FOUND,
    POOL_COOLDOWN_QUOTA,
    POOL_COOLDOWN_RATE_LIMIT,
    POOL_COOLDOWN_TRANSIENT,
    POOL_STAT_FLUSH_REQUESTS,
    REQUEST_TIMEOUT,
)
from engine.pool_client import fetch_members, report_stats

logger = logging.getLogger(__name__)

# 错误归类。决定「换成员还是重试同一个」以及冷却多久
ERROR_KIND_RATE_LIMIT = "rate_limit"
ERROR_KIND_AUTH = "auth"
ERROR_KIND_QUOTA = "quota"
ERROR_KIND_MODEL_NOT_FOUND = "model_not_found"
ERROR_KIND_TRANSIENT = "transient"
ERROR_KIND_BAD_REQUEST = "bad_request"

# 各类错误对应的成员冷却时长（秒）
# bad_request 是请求级问题（prompt 触发内容过滤、参数超限）而不是成员的错 所以不冷却
_COOLDOWN_BY_KIND = {
    ERROR_KIND_RATE_LIMIT: POOL_COOLDOWN_RATE_LIMIT,
    ERROR_KIND_AUTH: POOL_COOLDOWN_AUTH,
    ERROR_KIND_QUOTA: POOL_COOLDOWN_QUOTA,
    ERROR_KIND_MODEL_NOT_FOUND: POOL_COOLDOWN_MODEL_NOT_FOUND,
    ERROR_KIND_TRANSIENT: POOL_COOLDOWN_TRANSIENT,
    ERROR_KIND_BAD_REQUEST: 0,
}

# 需要人工介入而非等待即可恢复的错误 用于日志分级
_FATAL_KINDS = frozenset({ERROR_KIND_AUTH, ERROR_KIND_QUOTA, ERROR_KIND_MODEL_NOT_FOUND})

# 判定「配额耗尽」的错误文本特征
# OpenAI 把配额用尽也归到 429，但它要人工充值，按限流的几十秒冷却去等是白等
_QUOTA_MARKERS = (
    "insufficient_quota",
    "insufficient balance",
    "exceeded your current quota",
    "quota exceeded",
    "余额不足",
)

# SDK 会自行拼接的端点后缀 出现在 base_url 末尾时属于误填 需去掉
_CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

# 存进 DB 的失败原因截断长度 够定位问题又不至于把日志正文塞进表里
_FAILURE_REASON_MAX_LEN = 200

# 内存里保留的原始错误摘要长度
_ERROR_MESSAGE_MAX_LEN = 500


def normalize_base_url(base_url: str | None) -> str | None:
    """规整 LLM base_url 去掉误填的 /chat/completions 端点后缀。

    <p>OpenAI SDK 会在 base_url 后面自己拼 /chat/completions。用户从供应商文档里复制
    完整端点地址填进来时，实际请求会变成 .../v1/chat/completions/chat/completions 而直接 404。
    线上 SiliconFlow 的配置就是这么填的，导致所有走该配置的任务一次成功调用都没有，
    却因为失败批次静默回退原文而显示为「翻译完成」。

    Args:
        base_url: 用户配置或池成员里的 base_url 可为 None。

    Returns:
        规整后的 base_url 输入为空时原样返回。
    """
    if not base_url:
        return base_url
    normalized = base_url.rstrip("/")
    if normalized.endswith(_CHAT_COMPLETIONS_SUFFIX):
        normalized = normalized[: -len(_CHAT_COMPLETIONS_SUFFIX)]
        logger.warning(
            "[normalize_base_url] base_url 误填了端点后缀 已自动去掉 原值 %s 修正为 %s",
            base_url, normalized,
        )
    return normalized or base_url


def build_client(base_url: str | None, api_key: str | None) -> OpenAI:
    """构造 OpenAI 客户端。

    <p>max_retries 显式置 0：SDK 默认自带 2 次重试，会和本模块的重试相乘，
    最坏情况下一个批次要打 9 次付费请求，成本不可控。重试统一由调用方负责。

    Args:
        base_url: 接口地址。
        api_key: API Key。

    Returns:
        配置好超时与零重试的客户端。
    """
    return OpenAI(
        api_key=api_key or "",
        base_url=normalize_base_url(base_url),
        timeout=REQUEST_TIMEOUT,
        max_retries=0,
    )


def _looks_like_quota(exc: Exception) -> bool:
    """判断错误是否属于配额耗尽而非普通限流。"""
    code = getattr(exc, "code", None)
    if isinstance(code, str) and code.lower() in ("insufficient_quota", "insufficient_balance"):
        return True
    text = str(exc).lower()
    return any(marker in text for marker in _QUOTA_MARKERS)


def classify_error(exc: Exception) -> str:
    """把 LLM 调用异常归类，决定后续是换成员还是重试同一个成员。

    <p>改造前所有非 400 异常都落进同一个 except 分支、退避后重试同一把 KEY。
    单 KEY 时这没问题，多成员之后必须能区分「这个成员挂了要换」和「网络抖动重试就好」，
    否则一个失效的 KEY 会把每个批次的重试预算耗光。

    <p>判断顺序按 SDK 的异常继承关系从具体到宽泛：RateLimitError、AuthenticationError 等
    都是 APIStatusError 的子类，先判子类才不会被父类分支吞掉。

    Args:
        exc: 调用过程中抛出的异常。

    Returns:
        ERROR_KIND_* 之一。无法识别时按瞬时错误处理，倾向重试而不是把成员判死。
    """
    if isinstance(exc, RateLimitError):
        return ERROR_KIND_QUOTA if _looks_like_quota(exc) else ERROR_KIND_RATE_LIMIT
    if isinstance(exc, (AuthenticationError, PermissionDeniedError)):
        return ERROR_KIND_AUTH
    if isinstance(exc, NotFoundError):
        return ERROR_KIND_MODEL_NOT_FOUND
    if isinstance(exc, BadRequestError):
        # 400 也可能是「模型名不存在」——部分供应商不返回 404。调用方会给一次换成员的机会
        return ERROR_KIND_BAD_REQUEST
    if isinstance(exc, (APITimeoutError, APIConnectionError)):
        return ERROR_KIND_TRANSIENT
    if isinstance(exc, APIStatusError):
        status = getattr(exc, "status_code", None)
        if status == 402:
            return ERROR_KIND_QUOTA
        if isinstance(status, int) and 500 <= status < 600:
            return ERROR_KIND_TRANSIENT
        if isinstance(status, int) and 400 <= status < 500:
            return ERROR_KIND_BAD_REQUEST
    return ERROR_KIND_TRANSIENT


@dataclass(frozen=True)
class PoolMember:
    """一套可用于调用的 LLM 凭证。

    Attributes:
        member_id: Java 侧的成员 ID。用户自带凭证时为 None，表示不参与统计与调度。
        name: 成员名，只用于日志定位。
        base_url: 接口地址。
        api_key: API Key。
        model: 模型名称。
        weight: 成本分摊配比，调度时用来归一化用量。
    """

    member_id: Optional[int]
    name: str
    base_url: str
    api_key: str
    model: str
    weight: int = 1


@dataclass
class UsageDelta:
    """尚未上报给 Java 的用量增量。"""

    requests: int = 0
    failures: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    reasoning_tokens: int = 0
    last_failure_reason: Optional[str] = None

    def total_tokens(self) -> int:
        """本次增量消耗的 token 总数，调度排序用的口径。"""
        return self.prompt_tokens + self.completion_tokens + self.reasoning_tokens


@dataclass
class MemberState:
    """成员的运行时状态，只存在于本进程内存中。

    <p>冷却不落库是有意的：引擎重启后重新探活比继承一个可能早已过期的判死更合理。
    累计用量则必须落库，否则重启后调度会认为所有成员都是 0 用量而集中打第一个。

    Attributes:
        baseline_tokens: 已上报给 Java、落在滚动窗口内的 token 数。
        pending: 尚未上报的增量。
        cooldown_until: 冷却截止时刻（time.monotonic 时基）。
        last_error_kind: 最近一次失败的归类。
        last_error_message: 最近一次失败的原始摘要。
    """

    baseline_tokens: int = 0
    pending: UsageDelta = field(default_factory=UsageDelta)
    cooldown_until: float = 0.0
    last_error_kind: Optional[str] = None
    last_error_message: Optional[str] = None

    def used_tokens(self) -> int:
        """窗口内已消耗 token，含尚未上报的部分。"""
        return self.baseline_tokens + self.pending.total_tokens()


class FixedSource:
    """单一凭证来源，用于用户自带 KEY 的场景。

    <p>不做 failover 也不上报统计：没有别的成员可切，而且花的是用户自己的钱，
    混进池的统计会污染成本分散度的判断。行为与池化改造前完全一致——
    非 400 错误退避重试，400 直接放弃。
    """

    def __init__(self, base_url: str, api_key: str, model: str, name: str = "own-credentials") -> None:
        self._member = PoolMember(
            member_id=None, name=name, base_url=base_url, api_key=api_key, model=model,
        )
        self._client = build_client(base_url, api_key)

    def supports_failover(self) -> bool:
        """单一凭证没有可切换的成员。"""
        return False

    def acquire(self, exclude: Optional[Set[int]] = None) -> Optional[PoolMember]:
        """总是返回同一套凭证。"""
        return self._member

    def client_for(self, member: PoolMember) -> OpenAI:
        """返回构造好的客户端。"""
        return self._client

    def record_success(self, member: PoolMember, prompt_tokens: int,
                       completion_tokens: int, reasoning_tokens: int) -> None:
        """自带凭证不计入池统计。"""

    def record_failure(self, member: PoolMember, kind: str, message: str) -> None:
        """自带凭证不计入池统计。"""

    def pinned(self, member: PoolMember) -> "FixedSource":
        """自带凭证本身就只有一个成员，无需再钉。"""
        return self

    def flush(self) -> None:
        """无统计可上报。"""

    def describe(self) -> str:
        """日志用的来源描述，不含凭证。"""
        return f"own-credentials model={self._member.model}"


class PinnedSource:
    """把来源钉在某个成员上，用于截断拆分重试。

    <p>拆分是因为「这个成员的这次响应被截断了」才发生的，拆出来的子批次必须打回同一个
    成员：换成员意味着上下文窗口和输出上限都变了，基于原成员做的拆分决策随之失效，
    还会让成本分摊统计把一次异常放大成跨成员的噪声。

    <p>统计与客户端缓存仍然委托给池，所以钉住不影响用量归集。
    """

    def __init__(self, pool: "LlmPool", member: PoolMember) -> None:
        self._pool = pool
        self._member = member

    def supports_failover(self) -> bool:
        """钉住期间不换成员。"""
        return False

    def acquire(self, exclude: Optional[Set[int]] = None) -> Optional[PoolMember]:
        """总是返回被钉住的成员。"""
        return self._member

    def client_for(self, member: PoolMember) -> OpenAI:
        """复用池的客户端缓存。"""
        return self._pool.client_for(member)

    def record_success(self, member: PoolMember, prompt_tokens: int,
                       completion_tokens: int, reasoning_tokens: int) -> None:
        """统计仍归到池上。"""
        self._pool.record_success(member, prompt_tokens, completion_tokens, reasoning_tokens)

    def record_failure(self, member: PoolMember, kind: str, message: str) -> None:
        """统计仍归到池上。"""
        self._pool.record_failure(member, kind, message)

    def pinned(self, member: PoolMember) -> "PinnedSource":
        """已经钉住了。"""
        return self

    def flush(self) -> None:
        """交给池统一上报。"""
        self._pool.flush()

    def describe(self) -> str:
        """日志用的来源描述，不含凭证。"""
        return f"pinned member={self._member.name} model={self._member.model}"


class LlmPool:
    """默认凭证池。

    <p>模块级单例，被最多 8 个任务线程共享（gunicorn 单 worker + 8 gthread），
    因此游标、健康度和待上报增量都由同一把锁保护。改造前模块级没有任何可变共享状态。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._members: List[PoolMember] = []
        self._state: Dict[int, MemberState] = {}
        self._requests_since_flush = 0
        # 按成员缓存 (凭证, 客户端)，凭证被管理页改动时对比元组即可自动失效
        self._clients: Dict[int, tuple] = {}

    def refresh(self) -> int:
        """从 Java 拉取启用成员并与本地状态对账。

        <p>已有成员的运行时状态（冷却、未上报增量）保留，只把窗口用量基线换成 Java 的最新值；
        被删除或停用的成员连状态一起丢掉，避免它们继续占着内存和健康快照。

        <p>拉取失败时保留上一次的成员快照：管理接口短暂不可用不该让正在跑的任务失去凭证。

        Returns:
            当前可用成员数。
        """
        raw = fetch_members()
        if raw is None:
            with self._lock:
                current = len(self._members)
            logger.warning("[refresh] 拉取池配置失败 沿用上一次快照 members %d", current)
            return current

        members: List[PoolMember] = []
        baselines: Dict[int, int] = {}
        for item in raw:
            member_id = item.get("id")
            base_url = item.get("baseUrl")
            api_key = item.get("apiKey")
            model = item.get("model")
            if member_id is None or not base_url or not api_key or not model:
                logger.warning("[refresh] 池成员配置不完整 已跳过 memberId %s name %s", member_id, item.get("name"))
                continue
            weight = item.get("weight") or 1
            members.append(PoolMember(
                member_id=int(member_id),
                name=item.get("name") or f"member-{member_id}",
                base_url=base_url,
                api_key=api_key,
                model=model,
                weight=int(weight) if int(weight) > 0 else 1,
            ))
            baselines[int(member_id)] = int(item.get("windowTokens") or 0)

        with self._lock:
            self._members = members
            alive = {m.member_id for m in members}
            for stale in [mid for mid in self._state if mid not in alive]:
                self._state.pop(stale, None)
            for member in members:
                state = self._state.get(member.member_id)
                if state is None:
                    state = MemberState()
                    self._state[member.member_id] = state
                # 基线换成 Java 的窗口用量。未上报的增量留着，它还没被算进那个数字
                state.baseline_tokens = baselines.get(member.member_id, 0)
            count = len(members)

        logger.info("[refresh] 池配置已刷新 members %d", count)
        return count

    def size(self) -> int:
        """当前成员数。"""
        with self._lock:
            return len(self._members)

    def supports_failover(self) -> bool:
        """成员多于一个时才有切换的意义。"""
        with self._lock:
            return len(self._members) > 1

    def acquire(self, exclude: Optional[Set[int]] = None) -> Optional[PoolMember]:
        """按「窗口用量 / weight 最小优先」选一个成员。

        <p>全部成员都在冷却时不放弃，而是挑剩余冷却时间最短的继续试：冷却是暂时状态，
        而任务往往已经跑了一半，直接判失败要用户重传整个 mod，代价远大于多打一次可能失败的请求。

        Args:
            exclude: 本批次已经试过的成员 ID，用于 failover 时避开。

            Returns:
            选中的成员，池为空或全被排除时返回 None。
        """
        excluded = exclude or set()
        now = time.monotonic()
        with self._lock:
            candidates = [m for m in self._members if m.member_id not in excluded]
            if not candidates:
                return None
            available = [m for m in candidates if self._state[m.member_id].cooldown_until <= now]
            if available:
                # member_id 参与排序做稳定 tie-break，用量相同时选择才是确定的
                return min(available, key=lambda m: (self._load_factor(m), m.member_id))
            picked = min(candidates, key=lambda m: (self._state[m.member_id].cooldown_until, m.member_id))
            logger.warning(
                "[acquire] 全部成员处于冷却中 选用剩余冷却最短的继续尝试 member %s remaining %.0fs",
                picked.name, max(0.0, self._state[picked.member_id].cooldown_until - now),
            )
            return picked

    def _load_factor(self, member: PoolMember) -> float:
        """成员的归一化负载，调用方已持锁。"""
        state = self._state[member.member_id]
        return state.used_tokens() / max(member.weight, 1)

    def client_for(self, member: PoolMember) -> OpenAI:
        """取成员对应的客户端，按成员缓存。

        <p>不能每批新建：新建会丢掉连接复用、每批重做一次 TLS 握手，
        而一个大 mod 有三千多个批次。base_url 或 Key 变化时缓存自动失效。
        """
        with self._lock:
            cached = self._clients.get(member.member_id)
            if cached is not None and cached[0] == (member.base_url, member.api_key):
                return cached[1]
        client = build_client(member.base_url, member.api_key)
        with self._lock:
            self._clients[member.member_id] = ((member.base_url, member.api_key), client)
        return client

    def record_success(self, member: PoolMember, prompt_tokens: int,
                       completion_tokens: int, reasoning_tokens: int) -> None:
        """记一次成功调用及其 token 消耗。

        <p>成功即解除冷却：能成功说明成员已恢复，继续压着冷却只会让负载分布偏离配比。
        """
        if member.member_id is None:
            return
        with self._lock:
            state = self._state.get(member.member_id)
            if state is None:
                return
            state.pending.requests += 1
            state.pending.prompt_tokens += max(prompt_tokens, 0)
            state.pending.completion_tokens += max(completion_tokens, 0)
            state.pending.reasoning_tokens += max(reasoning_tokens, 0)
            state.cooldown_until = 0.0
            self._requests_since_flush += 1
        self._maybe_flush()

    def record_failure(self, member: PoolMember, kind: str, message: str) -> None:
        """记一次失败调用并按错误类型给成员冷却。"""
        if member.member_id is None:
            return
        cooldown = _COOLDOWN_BY_KIND.get(kind, POOL_COOLDOWN_TRANSIENT)
        with self._lock:
            state = self._state.get(member.member_id)
            if state is None:
                return
            state.pending.requests += 1
            state.pending.failures += 1
            state.pending.last_failure_reason = f"{kind} {message}"[:_FAILURE_REASON_MAX_LEN]
            state.last_error_kind = kind
            state.last_error_message = message[:_ERROR_MESSAGE_MAX_LEN]
            if cooldown > 0:
                state.cooldown_until = time.monotonic() + cooldown
            self._requests_since_flush += 1

        if kind in _FATAL_KINDS:
            # 这类错误要人工介入 打 ERROR 让它能被日志告警捞出来
            # 池化会把「一个成员配错」从整体失败削弱成产出率下降 光看任务状态发现不了
            logger.error(
                "[record_failure] 池成员失效 需人工处理 member %s kind %s cooldown %ds error %s",
                member.name, kind, cooldown, message[:_ERROR_MESSAGE_MAX_LEN],
            )
        else:
            logger.warning(
                "[record_failure] 池成员调用失败 member %s kind %s cooldown %ds error %s",
                member.name, kind, cooldown, message[:_ERROR_MESSAGE_MAX_LEN],
            )
        self._maybe_flush()

    def _maybe_flush(self) -> None:
        """累计请求数达到阈值时上报一次。"""
        with self._lock:
            due = self._requests_since_flush >= POOL_STAT_FLUSH_REQUESTS
        if due:
            self.flush()

    def flush(self) -> None:
        """把待上报增量发给 Java 并清空。

        <p>先在锁内取走增量、同时把 token 数并入基线，再在锁外发 HTTP：
        并入基线保证即使上报失败，调度看到的负载仍然是连续的，不会因为丢了一次上报
        就把已经用掉的额度当成没用过而继续往同一个成员上压。

        <p>上报失败不重放：统计不是账本，重放的双计风险比丢一点数据更糟。
        """
        items: List[dict] = []
        with self._lock:
            for member in self._members:
                state = self._state.get(member.member_id)
                if state is None or state.pending.requests == 0:
                    continue
                items.append({
                    "memberId": member.member_id,
                    "requests": state.pending.requests,
                    "failures": state.pending.failures,
                    "promptTokens": state.pending.prompt_tokens,
                    "completionTokens": state.pending.completion_tokens,
                    "reasoningTokens": state.pending.reasoning_tokens,
                    "lastFailureReason": state.pending.last_failure_reason,
                })
                state.baseline_tokens += state.pending.total_tokens()
                state.pending = UsageDelta()
            self._requests_since_flush = 0

        if not items:
            return
        report_stats(items)

    def health_snapshot(self) -> List[dict]:
        """导出各成员的实时健康状态，供管理页展示。

        Returns:
            成员健康状态列表，字段名与 Java 侧 EnginePoolMemberHealth 对齐。
        """
        now = time.monotonic()
        with self._lock:
            snapshot = []
            for member in self._members:
                state = self._state.get(member.member_id)
                if state is None:
                    continue
                remaining = max(0.0, state.cooldown_until - now)
                snapshot.append({
                    "memberId": member.member_id,
                    "available": remaining <= 0,
                    "cooldownRemainingSeconds": int(remaining),
                    "lastErrorKind": state.last_error_kind,
                    "lastErrorMessage": state.last_error_message,
                })
            return snapshot

    def pinned(self, member: PoolMember) -> PinnedSource:
        """把来源钉在指定成员上，用于截断拆分重试。"""
        return PinnedSource(self, member)

    def describe(self) -> str:
        """日志用的来源描述，不含凭证。"""
        with self._lock:
            return f"pool members={len(self._members)}"


# 模块级单例。任务态和健康度都在进程内存里，gunicorn 必须保持单 worker
_pool = LlmPool()


def get_pool() -> LlmPool:
    """取默认凭证池单例。"""
    return _pool
