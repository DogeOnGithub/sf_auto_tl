"""凭证池客户端，封装与 Backend 之间的池配置拉取与用量上报。

<p>引擎不直连数据库：现有分层里引擎只通过 API_BASE_URL 回调 Backend（见 cache_client），
为了拿池配置去加一个数据库驱动会把这条边界打破。
"""

from __future__ import annotations

import logging
import threading
import time
from typing import List, Optional

import requests

from engine.llm_config import API_BASE_URL, POOL_CONFIG_TTL_SECONDS

logger = logging.getLogger(__name__)

# 拉取与上报的超时（秒）。都是内网调用 给短一点 避免拖住翻译线程
_FETCH_TIMEOUT = 10
_REPORT_TIMEOUT = 10

# TTL 缓存。任务粒度拉取 一个任务动辄几分钟到几小时 60s 陈旧窗口足够小
_cache_lock = threading.Lock()
_cached_members: Optional[List[dict]] = None
_cached_at: float = 0.0


def fetch_members(force: bool = False) -> Optional[List[dict]]:
    """拉取启用中的池成员配置。

    <p>命中 TTL 时直接返回缓存，因此可以在每个任务开始时无脑调用一次。

    Args:
        force: 忽略 TTL 强制回源，管理页改动后想立刻生效时用。

    Returns:
        成员配置列表（含明文 Key 与窗口用量基线）。
        拉取失败且无可用缓存时返回 None，调用方据此决定是沿用旧快照还是拒绝任务。
    """
    global _cached_members, _cached_at

    now = time.monotonic()
    if not force:
        with _cache_lock:
            if _cached_members is not None and (now - _cached_at) < POOL_CONFIG_TTL_SECONDS:
                return _cached_members

    url = f"{API_BASE_URL}/api/internal/llm-pool/members"
    try:
        resp = requests.get(url, timeout=_FETCH_TIMEOUT)
        resp.raise_for_status()
        data = resp.json()
        if not isinstance(data, list):
            logger.warning("[fetch_members] 池配置响应格式异常 期望数组 实际 %s", type(data).__name__)
            return _fallback_cache()
        with _cache_lock:
            _cached_members = data
            _cached_at = time.monotonic()
        logger.info("[fetch_members] 池配置拉取成功 members %d", len(data))
        return data
    except Exception as e:
        # 不打 url 之外的内容：响应体带明文 Key
        logger.warning("[fetch_members] 池配置拉取失败 url %s error %s", url, str(e))
        return _fallback_cache()


def _fallback_cache() -> Optional[List[dict]]:
    """拉取失败时退回上一次成功的快照。"""
    with _cache_lock:
        if _cached_members is not None:
            logger.info("[_fallback_cache] 使用上一次的池配置快照 members %d", len(_cached_members))
        return _cached_members


def report_stats(items: List[dict]) -> None:
    """上报各成员的用量增量。

    <p>失败只告警不重试：统计不是账本，而重试可能造成双计，反而让成本分摊的判断更失真。

    Args:
        items: 每个成员的增量，字段与 Java 侧 LlmPoolStatReportRequest.Item 对齐。
    """
    if not items:
        return
    url = f"{API_BASE_URL}/api/internal/llm-pool/stats"
    try:
        resp = requests.post(url, json={"items": items}, timeout=_REPORT_TIMEOUT)
        resp.raise_for_status()
        logger.info("[report_stats] 用量上报成功 members %d", len(items))
    except Exception as e:
        logger.warning("[report_stats] 用量上报失败 已丢弃本次增量 members %d error %s", len(items), str(e))


def reset_cache() -> None:
    """清空配置缓存，仅测试使用。"""
    global _cached_members, _cached_at

    with _cache_lock:
        _cached_members = None
        _cached_at = 0.0
