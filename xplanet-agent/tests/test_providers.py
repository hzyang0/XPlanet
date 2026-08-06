import json

import httpx
import pytest

from xplanet_agent.models import ClaimDraft, TaskCommand, ToolAction, WriterDraft
from xplanet_agent.providers import BingRssSearchProvider, DeepSeekModelProvider


def command(**overrides) -> TaskCommand:
    values = {
        "eventId": "event-provider-1",
        "eventType": "AI_TASK_REQUESTED",
        "taskId": 9,
        "runId": "run-provider-1",
        "userId": 7,
        "question": "如何设计可恢复的 Agent 检查点？",
        "provider": "deepseek-tools",
        "maxSources": 2,
        "maxToolCalls": 4,
        "maxTokens": 4000,
    }
    values.update(overrides)
    return TaskCommand(**values)


def chat_response(data: dict, input_tokens: int = 10, output_tokens: int = 5) -> dict:
    return {
        "choices": [{"message": {"role": "assistant", "content": json.dumps(data, ensure_ascii=False)}}],
        "usage": {"prompt_tokens": input_tokens, "completion_tokens": output_tokens},
    }


def test_deepseek_model_provider_uses_chat_json_mode_and_preserves_usage() -> None:
    captured: list[tuple[str, dict]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append((str(request.url), json.loads(request.content)))
        assert request.headers["authorization"] == "Bearer test-key"
        assert request.headers["x-client-request-id"] == "run-provider-1"
        return httpx.Response(200, json=chat_response({
            "steps": [{
                "stepId": "checkpoint",
                "objective": "研究持久化恢复",
                "searchQuery": "Agent 检查点 持久化恢复",
            }]
        }, 120, 30))

    provider = DeepSeekModelProvider(
        api_key="test-key",
        model="deepseek-test",
        base_url="https://api.example.test",
        transport=httpx.MockTransport(handler),
    )
    plan, usage = provider.plan(command(), command().question)

    assert plan.steps[0].objective == "研究持久化恢复"
    assert usage.provider == "deepseek"
    assert usage.inputTokens == 120
    assert usage.outputTokens == 30
    assert captured[0][0].endswith("/chat/completions")
    assert captured[0][1]["response_format"] == {"type": "json_object"}
    assert captured[0][1]["max_tokens"] == 4000
    assert captured[0][1]["thinking"] == {"type": "disabled"}
    assert "every heading, sentence, claim" in captured[0][1]["messages"][1]["content"]
    assert "Required JSON Schema" in captured[0][1]["messages"][1]["content"]


def test_deepseek_writer_requires_question_language() -> None:
    captured: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        captured.append(payload)
        return httpx.Response(200, json=chat_response({
            "title": "研究报告：检查点恢复",
            "content": "## 有证据支持的发现\n\n- [claim-1] 检查点需要持久化。[ev-1]",
            "claims": [{
                "claimId": "claim-1",
                "statement": "检查点需要持久化。",
                "evidenceRefs": ["ev-1"],
                "confidence": 0.9,
            }],
        }))

    provider = DeepSeekModelProvider(api_key="test-key", transport=httpx.MockTransport(handler))
    from xplanet_agent.models import EvidenceResult, ResearchPlan, PlanStep, SourceResult
    plan = ResearchPlan(steps=[PlanStep(stepId="one", objective="研究", searchQuery="检查点")])
    source = SourceResult(
        sourceRef="src-1",
        url="https://example.com",
        title="示例",
        contentHash="a" * 64,
        retrievedAt="2026-08-06T00:00:00Z",
    )
    evidence = EvidenceResult(evidenceRef="ev-1", sourceRef="src-1", locator="已抓取原文", content="检查点需要持久化。", contentHash="b" * 64, score=0.9)
    draft, _ = provider.write(command(), command().question, plan, [source], [evidence], None)

    assert draft.title.startswith("研究报告")
    assert "Use Simplified Chinese for every heading" in captured[0]["messages"][1]["content"]


def test_deepseek_writer_appends_missing_claim_evidence_map() -> None:
    draft = WriterDraft(
        title="报告",
        content="## 结论\n\n需要持久化检查点。",
        claims=[ClaimDraft(
            claimId="claim-1",
            statement="需要持久化检查点。",
            evidenceRefs=["ev-1"],
            confidence=0.9,
        )],
    )

    normalized = DeepSeekModelProvider._ensure_markdown_citation_visibility("检查点如何恢复？", draft)

    assert "## 引用映射" in normalized.content
    assert "[claim-1]" in normalized.content
    assert "[ev-1]" in normalized.content


def test_bing_rss_search_returns_bounded_external_candidates() -> None:
    rss = """<?xml version="1.0"?><rss><channel>
      <item><title>LangGraph 文档</title><link>https://docs.example.com/langgraph</link><description>节点与检查点</description></item>
      <item><title>第二项</title><link>https://example.com/two</link><description><![CDATA[<b>恢复</b>机制]]></description></item>
    </channel></rss>"""
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, text=rss)

    provider = BingRssSearchProvider(transport=httpx.MockTransport(handler))
    action = ToolAction(name="web_search", query="Agent 检查点", reason="查资料")
    result = provider.search(command(), action, 1)

    assert len(result.searchHits) == 1
    assert result.searchHits[0].url == "https://docs.example.com/langgraph"
    assert result.searchHits[0].sourceType == "web"
    assert "format=rss" in str(captured[0].url)
    assert not result.usage


def test_bing_rss_search_rejects_invalid_or_empty_results() -> None:
    action = ToolAction(name="web_search", query="test", reason="test")
    invalid = BingRssSearchProvider(transport=httpx.MockTransport(lambda _: httpx.Response(200, text="bad")))
    with pytest.raises(ValueError, match="invalid RSS"):
        invalid.search(command(), action, 1)
    empty = BingRssSearchProvider(transport=httpx.MockTransport(lambda _: httpx.Response(200, text="<rss/>")))
    with pytest.raises(ValueError, match="no source candidates"):
        empty.search(command(), action, 1)


def test_deepseek_provider_requires_key_and_rejects_invalid_json() -> None:
    with pytest.raises(ValueError, match="DEEPSEEK_API_KEY"):
        DeepSeekModelProvider(api_key="")
    provider = DeepSeekModelProvider(
        api_key="test-key",
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"choices": [{"message": {"content": "not-json"}}]})
        ),
    )
    with pytest.raises(ValueError, match="not valid JSON"):
        provider.plan(command(), command().question)


@pytest.mark.parametrize(
    "raw,expected",
    [
        ('{"title":"plain"}', "plain"),
        ('Here is the requested object:\n```json\n{"title":"fenced"}\n```', "fenced"),
    ],
)
def test_deepseek_provider_accepts_wrapped_json_object(raw: str, expected: str) -> None:
    assert DeepSeekModelProvider._parse_json_object(raw)["title"] == expected


def test_deepseek_provider_retries_one_malformed_structured_response() -> None:
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        if len(calls) == 1:
            return httpx.Response(200, json={"choices": [{"message": {"content": "not json"}}], "usage": {"prompt_tokens": 10, "completion_tokens": 5}})
        return httpx.Response(200, json=chat_response({"steps": [{"stepId": "one", "objective": "research", "searchQuery": "outbox"}]}, 20, 8))

    provider = DeepSeekModelProvider(api_key="test-key", transport=httpx.MockTransport(handler))
    _, usage = provider.plan(command(), command().question)

    assert len(calls) == 2
    assert "previous answer was not valid JSON" in calls[1]["messages"][-1]["content"]
    assert usage.retryCount == 1
    assert usage.inputTokens == 30
    assert usage.outputTokens == 13
