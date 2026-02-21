"""Flask API 单元测试。"""

from __future__ import annotations

import json
from unittest.mock import patch

import pytest

from engine.app import create_app


@pytest.fixture
def client():
    """创建 Flask 测试客户端。"""
    app = create_app()
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


class TestPostTranslate:
    """POST /engine/translate 端点测试。"""

    def test_submit_returns_202_accepted(self, client):
        """有效请求应返回 202 和 accepted 状态。"""
        with patch("engine.app.translator") as mock_translator:
            mock_translator.submit_task.return_value = {"taskId": "t-1", "status": "accepted"}

            resp = client.post(
                "/engine/translate",
                json={"taskId": "t-1", "filePath": "/tmp/test.esm", "targetLang": "zh-CN"},
            )

        assert resp.status_code == 202
        data = resp.get_json()
        assert data["taskId"] == "t-1"
        assert data["status"] == "accepted"

    def test_submit_passes_optional_params(self, client):
        """应将 customPrompt 和 dictionaryEntries 传递给 translator。"""
        with patch("engine.app.translator") as mock_translator:
            mock_translator.submit_task.return_value = {"taskId": "t-2", "status": "accepted"}

            entries = [{"sourceText": "Sword", "targetText": "剑"}]
            resp = client.post(
                "/engine/translate",
                json={
                    "taskId": "t-2",
                    "filePath": "/tmp/test.esm",
                    "targetLang": "ja-JP",
                    "customPrompt": "自定义",
                    "dictionaryEntries": entries,
                },
            )

        assert resp.status_code == 202
        mock_translator.submit_task.assert_called_once_with(
            task_id="t-2",
            file_path="/tmp/test.esm",
            target_lang="ja-JP",
            custom_prompt="自定义",
            dictionary_entries=entries,
            callback_url=None,
            skip_cache=False,
        )

    def test_submit_missing_task_id_returns_400(self, client):
        """缺少 taskId 应返回 400。"""
        resp = client.post(
            "/engine/translate",
            json={"filePath": "/tmp/test.esm"},
        )
        assert resp.status_code == 400

    def test_submit_missing_file_path_returns_400(self, client):
        """缺少 filePath 应返回 400。"""
        resp = client.post(
            "/engine/translate",
            json={"taskId": "t-1"},
        )
        assert resp.status_code == 400

    def test_submit_non_json_returns_400(self, client):
        """非 JSON 请求体应返回 400。"""
        resp = client.post(
            "/engine/translate",
            data="not json",
            content_type="text/plain",
        )
        assert resp.status_code == 400

    def test_submit_default_target_lang(self, client):
        """未指定 targetLang 时应使用默认值 zh-CN。"""
        with patch("engine.app.translator") as mock_translator:
            mock_translator.submit_task.return_value = {"taskId": "t-3", "status": "accepted"}

            resp = client.post(
                "/engine/translate",
                json={"taskId": "t-3", "filePath": "/tmp/test.esm"},
            )

        assert resp.status_code == 202
        call_kwargs = mock_translator.submit_task.call_args.kwargs
        assert call_kwargs["target_lang"] == "zh-CN"
    def test_submit_passes_callback_url(self, client):
        """应将 callbackUrl 传递给 translator。"""
        with patch("engine.app.translator") as mock_translator:
            mock_translator.submit_task.return_value = {"taskId": "t-4", "status": "accepted"}

            resp = client.post(
                "/engine/translate",
                json={
                    "taskId": "t-4",
                    "filePath": "/tmp/test.esm",
                    "callbackUrl": "http://backend:8080/api/tasks/t-4/progress",
                },
            )

        assert resp.status_code == 202
        call_kwargs = mock_translator.submit_task.call_args.kwargs
        assert call_kwargs["callback_url"] == "http://backend:8080/api/tasks/t-4/progress"


class TestGetTask:
    """GET /engine/tasks/{taskId} 端点测试。"""

    def test_get_existing_task_returns_200(self, client):
        """查询存在的任务应返回 200 和任务信息。"""
        task_data = {
            "taskId": "t-1",
            "status": "translating",
            "progress": {"translated": 5, "total": 10},
            "outputFilePath": None,
            "originalBackupPath": None,
            "error": None,
        }
        with patch("engine.app.translator") as mock_translator:
            mock_translator.get_task.return_value = task_data

            resp = client.get("/engine/tasks/t-1")

        assert resp.status_code == 200
        data = resp.get_json()
        assert data["taskId"] == "t-1"
        assert data["status"] == "translating"
        assert data["progress"]["translated"] == 5
        assert data["progress"]["total"] == 10

    def test_get_nonexistent_task_returns_404(self, client):
        """查询不存在的任务应返回 404。"""
        with patch("engine.app.translator") as mock_translator:
            mock_translator.get_task.return_value = None

            resp = client.get("/engine/tasks/nonexistent")

        assert resp.status_code == 404
        data = resp.get_json()
        assert data["error"] == "TASK_NOT_FOUND"

    def test_get_completed_task_includes_paths(self, client):
        """已完成任务应包含输出路径和备份路径。"""
        task_data = {
            "taskId": "t-1",
            "status": "completed",
            "progress": {"translated": 10, "total": 10},
            "outputFilePath": "/tmp/out.esm",
            "originalBackupPath": "/tmp/backup.esm",
            "error": None,
        }
        with patch("engine.app.translator") as mock_translator:
            mock_translator.get_task.return_value = task_data

            resp = client.get("/engine/tasks/t-1")

        assert resp.status_code == 200
        data = resp.get_json()
        assert data["outputFilePath"] == "/tmp/out.esm"
        assert data["originalBackupPath"] == "/tmp/backup.esm"

    def test_get_failed_task_includes_error(self, client):
        """失败任务应包含错误信息。"""
        task_data = {
            "taskId": "t-1",
            "status": "failed",
            "progress": {"translated": 0, "total": 0},
            "outputFilePath": None,
            "originalBackupPath": None,
            "error": "parse error",
        }
        with patch("engine.app.translator") as mock_translator:
            mock_translator.get_task.return_value = task_data

            resp = client.get("/engine/tasks/t-1")

        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "failed"
        assert data["error"] == "parse error"
