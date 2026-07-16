import json

import httpx
import pytest

from xplanet_agent.models import TaskCommand
from xplanet_agent.providers import OpenAIWebResearchProvider
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


def response_payload(*, with_citation: bool = True, tool_calls: int = 1) -> dict:
    annotations = []
    if with_citation:
        annotations.append(
            {
                "type": "url_citation",
                "start_index": 0,
                "end_index": 24,
                "url": "https://example.com/checkpoints",
                "title": "Checkpoint Guide",
            }
        )
    output = [
        {
            "type": "web_search_call",
            "action": {
                "sources": [
                    {
                        "type": "url",
                        "url": "https://example.com/checkpoints",
                        "title": "Checkpoint Guide",
                    }
                ]
            },
        }
        for _ in range(tool_calls)
    ]
    output.append(
        {
            "type": "message",
            "content": [
                {
                    "type": "output_text",
                    "text": "Durable checkpoints make completed nodes recoverable.",
                    "annotations": annotations,
                }
            ],
        }
    )
    return {
        "output": output,
        "usage": {"input_tokens": 120, "output_tokens": 80},
    }


def provider(payload: dict, captured: list[dict] | None = None) -> OpenAIWebResearchProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(json.loads(request.content))
        assert request.headers["authorization"] == "Bearer test-key"
        assert request.headers["x-client-request-id"] == "run-provider-1"
        return httpx.Response(200, json=payload)

    return OpenAIWebResearchProvider(
        api_key="test-key",
        model="test-model",
        base_url="https://api.example.test/v1",
        transport=httpx.MockTransport(handler),
    )


def test_openai_web_provider_uses_bounded_web_search_and_preserves_usage() -> None:
    captured: list[dict] = []

    result = provider(response_payload(), captured).research(command())

    assert captured[0]["model"] == "test-model"
    assert captured[0]["tools"] == [{"type": "web_search", "search_context_size": "medium"}]
    assert captured[0]["tool_choice"] == "required"
    assert captured[0]["max_output_tokens"] == 4000
    assert len(result.sources) == 1
    assert result.sources[0].url == "https://example.com/checkpoints"
    assert '"semanticSupportVerified":false' in result.sources[0].metadataJson
    assert result.tool_calls == 1
    assert result.usage[0].inputTokens == 120
    assert result.usage[0].outputTokens == 80


def test_workflow_accepts_provider_result_and_checkpoints_usage() -> None:
    sink = RecordingSink()

    result = ResearchWorkflow(provider(response_payload())).run(command(), sink)

    assert result.provider == "openai-web"
    assert result.qualityScore == 1.0
    assert result.usage[0].model == "test-model"
    assert "https://example.com/checkpoints" in result.content
    assert sink.saved_nodes[-1] == "FINALIZE"


def test_openai_web_provider_rejects_missing_citations_and_tool_budget_overrun() -> None:
    with pytest.raises(ValueError, match="cited output text"):
        provider(response_payload(with_citation=False)).research(command())

    with pytest.raises(ValueError, match="tool-call budget"):
        provider(response_payload(tool_calls=2)).research(command(maxToolCalls=1))


def test_openai_web_provider_requires_explicit_api_key() -> None:
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        OpenAIWebResearchProvider(api_key="")
