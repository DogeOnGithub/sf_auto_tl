"""Flask REST API，提供翻译引擎的 HTTP 接口。"""

from __future__ import annotations

import logging
import os
import time

from flask import Flask, jsonify, request

from engine.llm_pool import build_client, classify_error, get_pool
from engine.llm_config import REQUEST_TIMEOUT
from engine.translator import Translator

logger = logging.getLogger(__name__)

translator = Translator()

# 连通性验证用的极小请求参数：只要能拿到一次正常响应就说明地址、Key、模型名三者都对
# 不复用翻译的 prompt 是为了把验证成本压到几乎为零
_TEST_MAX_TOKENS = 16
_TEST_TIMEOUT = 30


def create_app() -> Flask:
    """创建并配置 Flask 应用。"""
    log_level = os.environ.get("LOG_LEVEL", "WARNING").upper()
    logging.basicConfig(level=getattr(logging, log_level, logging.WARNING))
    app = Flask(__name__)

    @app.get("/health")
    def health():
        """健康检查端点。"""
        return jsonify({"status": "ok"}), 200

    @app.post("/engine/translate")
    def submit_translate():
        """提交翻译任务。"""
        data = request.get_json(silent=True)
        if data is None:
            return jsonify({"error": "INVALID_REQUEST", "message": "请求体必须为 JSON"}), 400

        task_id = data.get("taskId")
        file_path = data.get("filePath")

        if not task_id or not file_path:
            return jsonify({"error": "MISSING_PARAMS", "message": "taskId 和 filePath 为必填参数"}), 400

        target_lang = data.get("targetLang", "zh-CN")
        custom_prompt = data.get("customPrompt")
        dictionary_entries = data.get("dictionaryEntries")
        callback_url = data.get("callbackUrl")
        skip_cache = data.get("skipCache", False)
        llm_base_url = data.get("llmBaseUrl")
        llm_api_key = data.get("llmApiKey")
        llm_model = data.get("llmModel")
        enable_glossary_extraction = data.get("enableGlossaryExtraction", True)
        source_type = data.get("sourceType", "esm")

        logger.info("[submit_translate] 收到翻译请求 task_id %s file_path %s skip_cache %s llm_model %s source_type %s", task_id, file_path, skip_cache, llm_model, source_type)

        result = translator.submit_task(
            task_id=task_id,
            file_path=file_path,
            target_lang=target_lang,
            custom_prompt=custom_prompt,
            dictionary_entries=dictionary_entries,
            callback_url=callback_url,
            skip_cache=skip_cache,
            llm_base_url=llm_base_url,
            llm_api_key=llm_api_key,
            llm_model=llm_model,
            enable_glossary_extraction=enable_glossary_extraction,
            source_type=source_type,
        )
        return jsonify(result), 202

    @app.get("/engine/tasks/<task_id>")
    def get_task(task_id: str):
        """查询任务状态。"""
        task = translator.get_task(task_id)
        if task is None:
            return jsonify({"error": "TASK_NOT_FOUND", "message": "翻译任务不存在"}), 404
        return jsonify(task), 200

    @app.post("/engine/assembly")
    def submit_assembly():
        """提交组装任务（仅重组阶段，使用已确认的翻译结果）。"""
        data = request.get_json(silent=True)
        if data is None:
            return jsonify({"error": "INVALID_REQUEST", "message": "请求体必须为 JSON"}), 400

        task_id = data.get("taskId")
        file_path = data.get("filePath")
        items = data.get("items")

        if not task_id or not file_path or not items:
            return jsonify({"error": "MISSING_PARAMS", "message": "taskId、filePath 和 items 为必填参数"}), 400

        callback_url = data.get("callbackUrl")
        source_type = data.get("sourceType", "esm")

        logger.info("[submit_assembly] 收到组装请求 task_id %s items_count %d source_type %s", task_id, len(items), source_type)

        result = translator.submit_assembly(
            task_id=task_id,
            file_path=file_path,
            items=items,
            callback_url=callback_url,
            source_type=source_type,
        )
        return jsonify(result), 202

    @app.get("/engine/pool")
    def get_pool_health():
        """返回默认凭证池各成员的实时健康状态。

        <p>冷却状态只存在于本进程内存里，管理页要展示「当前是否可用」只能回源问引擎。
        响应不含任何凭证。
        """
        return jsonify({"members": get_pool().health_snapshot()}), 200

    @app.post("/engine/pool/test")
    def test_pool_member():
        """用给定凭证打一次极小的补全请求，验证配置可用。

        <p>验证放在引擎侧而不是让 Java 直连 LLM：只有走这里才会复用 build_client 的
        base_url 规整和客户端构造，测出来的结果才和真实翻译走同一条路径。线上出过
        base_url 误填成完整端点导致全部调用 404、却因失败批次静默回退原文而显示
        「翻译完成」的事故，这个接口就是为了让那类配置错误在配置阶段就暴露。

        <p>凭证不可用属于正常的验证结果而不是接口错误，所以统一返回 200 + success=false。
        日志不打请求体，它带明文 Key。
        """
        data = request.get_json(silent=True)
        if data is None:
            return jsonify({"error": "INVALID_REQUEST", "message": "请求体必须为 JSON"}), 400

        base_url = data.get("baseUrl")
        api_key = data.get("apiKey")
        model = data.get("model")
        if not base_url or not api_key or not model:
            return jsonify({"error": "MISSING_PARAMS", "message": "baseUrl、apiKey 和 model 为必填参数"}), 400

        logger.info("[test_pool_member] 开始验证凭证 model %s", model)
        started = time.monotonic()
        try:
            client = build_client(base_url, api_key)
            response = client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": "ping"}],
                max_tokens=_TEST_MAX_TOKENS,
                timeout=_TEST_TIMEOUT,
            )
            latency_ms = int((time.monotonic() - started) * 1000)
            finish_reason = getattr(response.choices[0], "finish_reason", None)
            logger.info("[test_pool_member] 验证通过 model %s latency_ms %d", model, latency_ms)
            return jsonify({
                "success": True,
                "message": f"调用成功 finish_reason {finish_reason}",
                "latencyMs": latency_ms,
            }), 200
        except Exception as e:
            latency_ms = int((time.monotonic() - started) * 1000)
            kind = classify_error(e)
            logger.warning("[test_pool_member] 验证失败 model %s kind %s error %s", model, kind, str(e))
            return jsonify({
                "success": False,
                "message": f"{kind} {str(e)}"[:500],
                "latencyMs": latency_ms,
            }), 200

    return app


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    app = create_app()
    app.run(host="0.0.0.0", port=int(os.environ.get("ENGINE_PORT", "5001")))
