"""翻译缓存客户端，封装与 Backend 缓存 API 的 HTTP 通信。"""

from __future__ import annotations

import logging
import os
from typing import Dict, List

import requests

from engine.esm_parser import StringRecord

logger = logging.getLogger(__name__)

API_BASE_URL = os.environ.get("API_BASE_URL", "http://localhost:8080")


def _extract_record_type(record_id: str) -> str:
    """从 record_id 中提取 record_type（第一个冒号前的部分）。

    例如 "RFGP:0100448A:RNAM" → "RFGP"
    """
    return record_id.split(":", 1)[0]


def _extract_subrecord_type(record_id: str) -> str:
    """从 record_id 中提取 subrecord_type（最后一个冒号后的部分）。

    例如 "RFGP:0100448A:RNAM" → "RNAM"
    """
    return record_id.rsplit(":", 1)[-1]


def query_cache(records: List[StringRecord], target_lang: str) -> Dict[str, str]:
    """批量查询翻译缓存。

    Args:
        records: 待查询的 StringRecord 列表。
        target_lang: 目标语言。

    Returns:
        record_id -> target_text 的映射，仅包含命中的记录。
    """
    if not records:
        return {}

    items = [
        {
            "recordId": r.record_id,
            "recordType": _extract_record_type(r.record_id),
            "subrecordType": _extract_subrecord_type(r.record_id),
            "sourceText": r.text,
        }
        for r in records
    ]

    payload = {
        "targetLang": target_lang,
        "items": items,
    }

    try:
        url = f"{API_BASE_URL}/api/translation-cache/query"
        logger.info("[query_cache] 查询缓存 records_count %d target_lang %s", len(records), target_lang)
        resp = requests.post(url, json=payload, timeout=30)
        resp.raise_for_status()

        data = resp.json()
        result = {}
        for item in data.get("items", []):
            if item.get("hit"):
                result[item["recordId"]] = item["targetText"]

        logger.info("[query_cache] 缓存命中 hit_count %d total %d", len(result), len(records))
        return result

    except Exception as e:
        logger.warning("[query_cache] 缓存查询失败 error %s", str(e))
        return {}


def save_cache(
    translations: Dict[str, str],
    records: List[StringRecord],
    target_lang: str,
    task_id: str,
) -> None:
    """批量保存翻译结果到缓存。

    Args:
        translations: record_id -> target_text 的映射。
        records: 原始 StringRecord 列表（用于提取 subrecord_type 和 source_text）。
        target_lang: 目标语言。
        task_id: 翻译任务 ID。
    """
    if not translations:
        return

    record_lookup = {r.record_id: r for r in records}

    items = []
    for record_id, target_text in translations.items():
        record = record_lookup.get(record_id)
        if record is None:
            continue
        items.append({
            "recordType": _extract_record_type(record_id),
            "subrecordType": _extract_subrecord_type(record_id),
            "sourceText": record.text,
            "targetText": target_text,
        })

    if not items:
        return

    payload = {
        "taskId": task_id,
        "targetLang": target_lang,
        "items": items,
    }

    try:
        url = f"{API_BASE_URL}/api/translation-cache/save"
        logger.info("[save_cache] 保存缓存 items_count %d task_id %s", len(items), task_id)
        resp = requests.post(url, json=payload, timeout=30)
        resp.raise_for_status()
        logger.info("[save_cache] 缓存保存成功 items_count %d", len(items))

    except Exception as e:
        logger.warning("[save_cache] 缓存保存失败 error %s", str(e))
