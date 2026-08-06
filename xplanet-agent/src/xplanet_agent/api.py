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


def _internal_search_provider() -> HttpInternalSearchProvider | OfflineInternalSearchProvider:
    internal_token = os.getenv("AGENT_INTERNAL_TOKEN", "")
    return (
        HttpInternalSearchProvider(
            base_url=os.getenv("ARTICLE_SERVICE_URL", "http://article:8081"),
            internal_token=internal_token,
            public_base_url=os.getenv("PUBLIC_GATEWAY_URL", "http://localhost:8080"),
        )
        if internal_token
        else OfflineInternalSearchProvider()
    )


def _build_offline_workflow() -> ResearchWorkflow:
    return ResearchWorkflow(
        # Offline runs must be reproducible and must never feed previously generated
        # community reports back into the next report. That feedback loop caused each
        # run to copy the preceding report and grow recursively.
        internal_search_provider=OfflineInternalSearchProvider(),
        after_checkpoint=_optional_crash_after_checkpoint,
    )


def _build_online_workflow() -> ResearchWorkflow | None:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        return None
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
        internal_search_provider=_internal_search_provider(),
        after_checkpoint=_optional_crash_after_checkpoint,
    )


# Keep the offline workflow available at all times. Existing tests and callers that omit
# provider continue to use this deterministic execution path.
workflow = _build_offline_workflow()
online_workflow = _build_online_workflow()


def _workflow_for(provider: str) -> ResearchWorkflow:
    if provider == "offline-demo":
        return workflow
    if provider == "openai-tools":
        if online_workflow is None:
            raise ValueError("openai-tools is unavailable: configure OPENAI_API_KEY and restart Agent")
        return online_workflow
    raise ValueError(f"unsupported Agent provider: {provider}")


def _require_internal_token(presented: str | None) -> str:
    expected = os.getenv("AGENT_INTERNAL_TOKEN", "")
    if not expected or not presented or not hmac.compare_digest(expected, presented):
        raise HTTPException(status_code=401, detail="invalid internal token")
    return expected


@app.get("/health")
def health() -> dict[str, object]:
    default_provider = os.getenv("AGENT_PROVIDER", "offline-demo").strip().lower()
    if default_provider not in {"offline-demo", "openai-tools"}:
        default_provider = "offline-demo"
    return {
        "status": "UP",
        "defaultProvider": default_provider,
        "providers": {
            "offline-demo": True,
            "openai-tools": online_workflow is not None,
        },
        "onlineModel": os.getenv("OPENAI_MODEL", "gpt-5.6-terra") if online_workflow else "",
    }


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
        return _workflow_for(command.provider).run(command, sink)
    except TaskCancelled as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except (TimeoutError, httpx.TimeoutException) as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
