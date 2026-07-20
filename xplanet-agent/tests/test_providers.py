import json

import httpx
import pytest

from xplanet_agent.models import TaskCommand, ToolAction
from xplanet_agent.providers import OpenAIHostedSearchProvider, OpenAIModelProvider
from xplanet_agent.tools import OfflineDocumentFetcher
from xplanet_agent.workflow import ResearchWorkflow

from test_workflow import RecordingSink


def command(**overrides) -> TaskCommand:
    values = {
        "eventId": "event-provider-1",
        "eventType": "AI_TASK_REQUESTED",
        "taskId": 9,
        "runId": "run-provider-1",
        "userId": 7,
        "question": "How should recoverable agent checkpoints be designed?",
        "maxSources": 1,
        "maxToolCalls": 2,
        "maxTokens": 4000,
    }
    values.update(overrides)
    return TaskCommand(**values)


def json_response(data: dict, input_tokens: int = 10, output_tokens: int = 5) -> dict:
    return {
        "output": [
            {
                "type": "message",
                "content": [{"type": "output_text", "text": json.dumps(data)}],
            }
        ],
        "usage": {"input_tokens": input_tokens, "output_tokens": output_tokens},
    }


def test_openai_model_provider_uses_structured_planning_and_preserves_usage() -> None:
    captured: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content))
        assert request.headers["authorization"] == "Bearer test-key"
        assert request.headers["x-client-request-id"] == "run-provider-1"
        return httpx.Response(
            200,
            json=json_response(
                {
                    "steps": [
                        {
                            "stepId": "checkpoint",
                            "objective": "Study durable recovery",
                            "searchQuery": "durable agent checkpoint recovery",
                        }
                    ]
                },
                120,
                30,
            ),
        )

    provider = OpenAIModelProvider(
        api_key="test-key",
        model="test-model",
        base_url="https://api.example.test/v1",
        transport=httpx.MockTransport(handler),
    )
    plan, usage = provider.plan(command(), command().question)

    assert plan.steps[0].stepId == "checkpoint"
    assert usage.inputTokens == 120
    assert usage.outputTokens == 30
    assert captured[0]["text"]["format"]["type"] == "json_schema"
    assert captured[0]["text"]["format"]["name"] == "planner"
    assert captured[0]["text"]["format"]["strict"] is True
    assert captured[0]["text"]["format"]["schema"]["additionalProperties"] is False
    assert (
        captured[0]["text"]["format"]["schema"]["$defs"]["PlanStep"]["additionalProperties"]
        is False
    )
    assert captured[0]["max_output_tokens"] == 4000


def hosted_search_payload(*, source: bool = True, calls: int = 1) -> dict:
    output = []
    for _ in range(calls):
        output.append(
            {
                "type": "web_search_call",
                "action": {
                    "sources": (
                        [
                            {
                                "type": "url",
                                "url": "https://github.com/hzyang0/XPlanet",
                                "title": "XPlanet repository",
                            }
                        ]
                        if source
                        else []
                    )
                },
            }
        )
    output.append(
        {
            "type": "message",
            "content": [{"type": "output_text", "text": "XPlanet persists Agent checkpoints."}],
        }
    )
    return {"output": output, "usage": {"input_tokens": 50, "output_tokens": 20}}


def test_hosted_search_is_one_bounded_tool_action_and_returns_candidates() -> None:
    captured: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content))
        return httpx.Response(200, json=hosted_search_payload())

    provider = OpenAIHostedSearchProvider(
        api_key="test-key",
        model="test-model",
        transport=httpx.MockTransport(handler),
    )
    action = ToolAction(name="web_search", query="agent checkpoints", reason="find sources")
    result = provider.search(command(), action, 1)

    assert result.searchHits[0].url == "https://github.com/hzyang0/XPlanet"
    assert result.usage[0].nodeName == "EXECUTE_TOOL"
    assert result.usage[0].outputTokens == 20
    assert captured[0]["tools"] == [{"type": "web_search", "search_context_size": "medium"}]
    assert captured[0]["tool_choice"] == "required"
    assert captured[0]["max_tool_calls"] == 1


def test_openai_components_run_inside_dynamic_workflow() -> None:
    decision_count = 0

    def model_handler(request: httpx.Request) -> httpx.Response:
        nonlocal decision_count
        payload = json.loads(request.content)
        node = payload["text"]["format"]["name"]
        if node == "planner":
            data = {
                "steps": [
                    {"stepId": "one", "objective": "Find source", "searchQuery": "XPlanet"}
                ]
            }
        elif node == "decide_action":
            decision_count += 1
            if decision_count == 1:
                data = {"name": "web_search", "query": "XPlanet", "url": None, "reason": "find"}
            elif decision_count == 2:
                data = {
                    "name": "web_fetch",
                    "query": None,
                    "url": "https://github.com/hzyang0/XPlanet",
                    "reason": "read",
                }
            else:
                data = {
                    "name": "finish_research",
                    "query": None,
                    "url": None,
                    "reason": "enough",
                }
        elif node == "writer":
            data = {
                "title": "Recoverable Agent",
                "content": "[claim-1] XPlanet persists Agent checkpoints. [ev-1]\n\n## 不确定性与冲突\n\n- none",
                "claims": [
                    {
                        "claimId": "claim-1",
                        "statement": "XPlanet persists Agent checkpoints.",
                        "evidenceRefs": ["ev-1"],
                        "confidence": 0.9,
                    }
                ],
            }
        else:
            assert node == "critic"
            data = {
                "approved": True,
                "qualityScore": 0.9,
                "claimSupportScore": 0.9,
                "issues": [],
                "uncertainties": [],
                "conflicts": [],
                "supplementalQuery": None,
            }
        return httpx.Response(200, json=json_response(data))

    def search_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=hosted_search_payload())

    model = OpenAIModelProvider(api_key="test-key", transport=httpx.MockTransport(model_handler))
    search = OpenAIHostedSearchProvider(
        api_key="test-key", transport=httpx.MockTransport(search_handler)
    )
    sink = RecordingSink()
    result = ResearchWorkflow(
        model_provider=model,
        search_provider=search,
        document_fetcher=OfflineDocumentFetcher(),
    ).run(command(), sink)

    assert result.provider == "openai-tools"
    assert result.title == "Recoverable Agent"
    assert result.sources[0].url == "https://github.com/hzyang0/XPlanet"
    assert len(result.usage) == 6
    assert sink.saved_nodes.count("EXECUTE_TOOL") == 2


def test_hosted_search_rejects_missing_sources_and_multiple_internal_calls() -> None:
    def build_provider(payload: dict) -> OpenAIHostedSearchProvider:
        return OpenAIHostedSearchProvider(
            api_key="test-key",
            transport=httpx.MockTransport(lambda request: httpx.Response(200, json=payload)),
        )

    action = ToolAction(name="web_search", query="test", reason="test")
    with pytest.raises(ValueError, match="no source candidates"):
        build_provider(hosted_search_payload(source=False)).search(command(), action, 1)
    with pytest.raises(ValueError, match="exactly one"):
        build_provider(hosted_search_payload(calls=2)).search(command(), action, 1)


def test_openai_providers_require_explicit_api_key() -> None:
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        OpenAIModelProvider(api_key="")
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        OpenAIHostedSearchProvider(api_key="")


def test_openai_model_provider_rejects_invalid_structured_output() -> None:
    provider = OpenAIModelProvider(
        api_key="test-key",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=json_response({"unexpected": True}))
        ),
    )

    with pytest.raises(ValueError):
        provider.plan(command(), command().question)
