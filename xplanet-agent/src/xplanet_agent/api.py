from __future__ import annotations

import hmac
import os

import httpx
from fastapi import FastAPI, Header, HTTPException

from .models import ResearchResult, TaskCommand
from .progress import HttpProgressSink
from .providers import OpenAIHostedSearchProvider, OpenAIModelProvider
from .tools import HttpDocumentFetcher, HttpInternalSearchProvider, OfflineInternalSearchProvider
from .workflow import ResearchWorkflow, TaskCancelled

app = FastAPI(title="XPlanet Agent", version="0.1.0")


def _optional_crash_after_checkpoint(node: str) -> None:
    """Explicit chaos-test hook; disabled unless the container env names a node."""
    if os.getenv("AGENT_CRASH_AFTER_NODE", "").upper() == node.upper():
        os._exit(17)


def _build_workflow() -> ResearchWorkflow:
    provider_name = os.getenv("AGENT_PROVIDER", "offline-demo").strip().lower()
    internal_token = os.getenv("AGENT_INTERNAL_TOKEN", "")
    internal_search = (
        HttpInternalSearchProvider(
            base_url=os.getenv("ARTICLE_SERVICE_URL", "http://article:8081"),
            internal_token=internal_token,
            public_base_url=os.getenv("PUBLIC_GATEWAY_URL", "http://localhost:8080"),
        )
        if internal_token
        else OfflineInternalSearchProvider()
    )
    if provider_name == "offline-demo":
        return ResearchWorkflow(
            internal_search_provider=internal_search,
            after_checkpoint=_optional_crash_after_checkpoint,
        )
    elif provider_name == "openai-tools":
        api_key = os.getenv("OPENAI_API_KEY", "")
        model = os.getenv("OPENAI_MODEL", "gpt-5.6-terra")
        base_url = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
        model_provider = OpenAIModelProvider(api_key=api_key, model=model, base_url=base_url)
        search_provider = OpenAIHostedSearchProvider(
            api_key=api_key,
            model=model,
            base_url=base_url,
        )
        return ResearchWorkflow(
            model_provider=model_provider,
            search_provider=search_provider,
            document_fetcher=HttpDocumentFetcher(),
            internal_search_provider=internal_search,
            after_checkpoint=_optional_crash_after_checkpoint,
        )
    else:
        raise ValueError(f"unsupported AGENT_PROVIDER: {provider_name}")


workflow = _build_workflow()


def _require_internal_token(presented: str | None) -> str:
    expected = os.getenv("AGENT_INTERNAL_TOKEN", "")
    if not expected or not presented or not hmac.compare_digest(expected, presented):
        raise HTTPException(status_code=401, detail="invalid internal token")
    return expected


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "provider": workflow.provider_name}


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
    except (TimeoutError, httpx.TimeoutException) as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
