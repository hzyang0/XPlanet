import os

from fastapi.testclient import TestClient

from xplanet_agent.api import app


def test_health_is_public() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


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
