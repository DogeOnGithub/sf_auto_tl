"""Flask REST API，提供翻译引擎的 HTTP 接口。"""

from __future__ import annotations

import logging
import os

from flask import Flask, jsonify, request

from engine.translator import Translator

logger = logging.getLogger(__name__)

translator = Translator()


def create_app() -> Flask:
    """创建并配置 Flask 应用。"""
    log_level = os.environ.get("LOG_LEVEL", "WARNING").upper()
    logging.basicConfig(level=getattr(logging, log_level, logging.WARNING))
    app = Flask(__name__)

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

        logger.info("[submit_translate] 收到翻译请求 task_id %s file_path %s", task_id, file_path)

        result = translator.submit_task(
            task_id=task_id,
            file_path=file_path,
            target_lang=target_lang,
            custom_prompt=custom_prompt,
            dictionary_entries=dictionary_entries,
            callback_url=callback_url,
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

        logger.info("[submit_assembly] 收到组装请求 task_id %s items_count %d", task_id, len(items))

        result = translator.submit_assembly(
            task_id=task_id,
            file_path=file_path,
            items=items,
            callback_url=callback_url,
        )
        return jsonify(result), 202

    return app


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    app = create_app()
    app.run(host="0.0.0.0", port=int(os.environ.get("ENGINE_PORT", "5001")))
