from __future__ import annotations

import hmac
import os

from fastapi import FastAPI, Header, HTTPException

from .models import ResearchResult, TaskCommand
from .progress import HttpProgressSink
from .workflow import ResearchWorkflow, TaskCancelled

app = FastAPI(title="XPlanet Agent", version="0.1.0")
workflow = ResearchWorkflow()


def _require_internal_token(presented: str | None) -> str:
    expected = os.getenv("AGENT_INTERNAL_TOKEN", "")
    if not expected or not presented or not hmac.compare_digest(expected, presented):
        raise HTTPException(status_code=401, detail="invalid internal token")
    return expected


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "provider": "offline-demo"}


@app.post("/internal/tasks/execute", response_model=ResearchResult)
def execute(
    command: TaskCommand,
    x_agent_token: str | None = Header(default=None),
) -> ResearchResult:
    token = _require_internal_token(x_agent_token)
    if command.eventType != "AI_TASK_REQUESTED":
        raise HTTPException(status_code=400, detail="unsupported command type")
    sink = HttpProgressSink(
        base_url=os.getenv("AI_CONTROL_URL", "http://ai:8084"),
        internal_token=token,
        task_id=command.taskId,
        run_id=command.runId,
    )
    try:
        return workflow.run(command, sink)
    except TaskCancelled as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
