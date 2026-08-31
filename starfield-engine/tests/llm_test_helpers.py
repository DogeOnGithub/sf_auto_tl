"""LLM 凭证来源相关测试的共用辅助。

<p>池化之后 _translate_batch 和 extract_glossary 不再直接吃 OpenAI 客户端，而是吃一个
CredentialSource。这里把「构造一个客户端被替换成 mock 的来源」收成公共函数，
避免每个用例都去 patch 一遍 build_client。
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import httpx
from openai import (
    APIConnectionError,
    APIStatusError,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    RateLimitError,
)

from engine.llm_pool import FixedSource, LlmPool

_TEST_URL = "https://test.local/v1/chat/completions"


def _status_error(cls, status_code: int, message: str):
    """构造一个带真实 HTTP 状态码的 OpenAI 异常。

    <p>错误分类完全依赖 SDK 的异常类型和状态码，用裸 Exception 冒充测不到分类逻辑。

    Args:
        cls: OpenAI 异常类。
        status_code: HTTP 状态码。
        message: 错误消息。

    Returns:
        可直接当 side_effect 用的异常实例。
    """
    request = httpx.Request("POST", _TEST_URL)
    response = httpx.Response(status_code, request=request)
    return cls(message, response=response, body={"error": {"message": message}})


def rate_limit_error(message: str = "rate limit exceeded"):
    """429 限流。"""
    return _status_error(RateLimitError, 429, message)


def quota_error(message: str = "You exceeded your current quota"):
    """配额耗尽，SDK 同样归到 429，靠消息特征区分。"""
    return _status_error(RateLimitError, 429, message)


def auth_error(message: str = "invalid api key"):
    """401 鉴权失败。"""
    return _status_error(AuthenticationError, 401, message)


def not_found_error(message: str = "model not found"):
    """404 模型不存在。"""
    return _status_error(NotFoundError, 404, message)


def bad_request_error(message: str = "invalid request"):
    """400 参数或内容问题。"""
    return _status_error(BadRequestError, 400, message)


def server_error(message: str = "internal error"):
    """5xx 服务端错误，按瞬时错误处理。"""
    return _status_error(APIStatusError, 503, message)


def connection_error(message: str = "connection reset"):
    """网络层错误，按瞬时错误处理。"""
    return APIConnectionError(request=httpx.Request("POST", _TEST_URL))


def fixed_source(client: MagicMock, model: str = "gpt-4o-mini") -> FixedSource:
    """构造自带凭证语义的来源，客户端替换为给定 mock。

    <p>FixedSource 在构造时就建好客户端，所以 patch 只需要覆盖构造这一刻。

    Args:
        client: 冒充 OpenAI 客户端的 mock。
        model: 该来源使用的模型名。

    Returns:
        不做 failover、不上报统计的来源。
    """
    with patch("engine.llm_pool.build_client", return_value=client):
        return FixedSource(base_url="https://test.local/v1", api_key="sk-test", model=model)


def raw_member(
    member_id: int,
    name: str | None = None,
    base_url: str | None = None,
    model: str = "test-model",
    weight: int = 1,
    window_tokens: int = 0,
) -> dict:
    """构造一条 Java 侧格式的池成员配置。

    Args:
        member_id: 成员 ID。
        name: 成员名 默认按 ID 生成。
        base_url: 接口地址 默认按 ID 生成 便于区分不同成员的客户端。
        model: 模型名。
        weight: 成本分摊配比。
        window_tokens: 窗口内已消耗 token 作为调度基线。

    Returns:
        与 /api/internal/llm-pool/members 响应元素同构的字典。
    """
    return {
        "id": member_id,
        "name": name or f"member-{member_id}",
        "baseUrl": base_url or f"https://m{member_id}.local/v1",
        "apiKey": f"sk-{member_id}",
        "model": model,
        "weight": weight,
        "windowTokens": window_tokens,
    }


def build_pool(raw_members: list[dict], clients: dict[int, MagicMock] | None = None) -> LlmPool:
    """构造一个已刷新配置的池，成员客户端替换为 mock。

    <p>走真实的 refresh 路径而不是直接塞内部状态，这样成员对账、基线注入这些逻辑
    也一并被用例覆盖到。

    Args:
        raw_members: Java 侧格式的成员配置列表。
        clients: memberId -> mock 客户端。缺省时每个成员给一个独立 MagicMock。

    Returns:
        可直接当 CredentialSource 用的池。
    """
    resolved = clients or {m["id"]: MagicMock() for m in raw_members}

    pool = LlmPool()
    with patch("engine.llm_pool.fetch_members", return_value=raw_members):
        pool.refresh()

    # 直接把 mock 塞进客户端缓存，而不是让 build_client 长期被 patch 住：
    # client_for 命中缓存就不会去构造真实客户端，也不会有跨用例泄漏的全局 patch
    for member in raw_members:
        pool._clients[member["id"]] = (
            (member["baseUrl"], member["apiKey"]),
            resolved[member["id"]],
        )
    return pool
