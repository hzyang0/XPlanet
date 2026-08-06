import os

from fastapi.testclient import TestClient

from xplanet_agent import api

app = api.app


def test_health_is_public() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["providers"]["offline-demo"] is True


def test_online_provider_requires_server_side_key(monkeypatch) -> None:
    os.environ["AGENT_INTERNAL_TOKEN"] = "test-internal-token"
    monkeypatch.setattr(api, "online_workflow", None)
    response = TestClient(app).post(
        "/internal/tasks/execute",
        headers={"X-Agent-Token": "test-internal-token"},
        json={
            "eventId": "event-online-1",
            "eventType": "AI_TASK_REQUESTED",
            "taskId": 3,
            "runId": "run-online-1",
            "userId": 7,
            "question": "test online provider",
            "provider": "deepseek-tools",
        },
    )
    assert response.status_code == 422
    assert "DEEPSEEK_API_KEY" in response.json()["detail"]


def test_execute_requires_internal_token() -> None:
    os.environ["AGENT_INTERNAL_TOKEN"] = "test-internal-token"
    response = TestClient(app).post(
        "/internal/tasks/execute",
        json={
            "eventId": "event-1",
            "eventType": "AI_TASK_REQUESTED",
            "taskId": 1,
            "runId": "run-1",
            "userId": 7,
            "question": "test",
        },
    )
    assert response.status_code == 401


def test_execute_classifies_permanent_and_timeout_failures(monkeypatch) -> None:
    os.environ["AGENT_INTERNAL_TOKEN"] = "test-internal-token"
    payload = {
        "eventId": "event-2",
        "eventType": "AI_TASK_REQUESTED",
        "taskId": 2,
        "runId": "run-2",
        "userId": 7,
        "question": "test",
    }

    def invalid(*args):
        raise ValueError("invalid action")

    monkeypatch.setattr(api.workflow, "run", invalid)
    rejected = TestClient(app).post(
        "/internal/tasks/execute",
        headers={"X-Agent-Token": "test-internal-token"},
        json=payload,
    )
    assert rejected.status_code == 422

    def timed_out(*args):
        raise TimeoutError("deadline")

    monkeypatch.setattr(api.workflow, "run", timed_out)
    timeout = TestClient(app).post(
        "/internal/tasks/execute",
        headers={"X-Agent-Token": "test-internal-token"},
        json=payload,
    )
    assert timeout.status_code == 504
