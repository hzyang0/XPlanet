import pytest

from pydantic import ValidationError

from xplanet_agent.models import (
    ClaimDraft,
    CriticIssue,
    CriticReview,
    SearchHit,
    TaskCommand,
    ToolAction,
    ToolExecutionResult,
)
from xplanet_agent.providers import OfflineModelProvider
from xplanet_agent.workflow import ResearchWorkflow, TaskCancelled


class RecordingSink:
    def __init__(self, cancelled: bool = False) -> None:
        self.events: list[tuple[str, str, int]] = []
        self.saved_nodes: list[str] = []
        self.checkpoint: str | None = None
        self.cancelled = cancelled

    def emit(self, node: str, status: str, message: str, progress: int) -> None:
        self.events.append((node, status, progress))

    def is_cancelled(self) -> bool:
        return self.cancelled

    def load_checkpoint(self) -> str | None:
        return self.checkpoint

    def save_checkpoint(
        self,
        node: str,
        input_hash: str,
        state_json: str,
        duration_ms: int,
    ) -> None:
        assert len(input_hash) == 64
        assert duration_ms >= 0
        self.saved_nodes.append(node)
        self.checkpoint = state_json


def command(**overrides) -> TaskCommand:
    values = {
        "eventId": "event-1",
        "eventType": "AI_TASK_REQUESTED",
        "taskId": 1,
        "runId": "run-1",
        "userId": 7,
        "question": "How should an Outbox based agent platform be designed?",
    }
    values.update(overrides)
    return TaskCommand(**values)


def test_workflow_produces_traceable_report_with_bounded_sources() -> None:
    sink = RecordingSink()
    result = ResearchWorkflow().run(command(maxSources=2, maxToolCalls=2), sink)

    assert result.provider == "offline-demo"
    assert len(result.sources) == 2
    assert len(result.evidence) == 2
    assert all(len(item.contentHash) == 64 for item in result.evidence)
    assert {c.evidenceRef for c in result.citations} == {e.evidenceRef for e in result.evidence}
    assert all(c.claimId in result.content for c in result.citations)
    assert "Critic 质量审计" in result.content
    assert all(e.sourceRef in {s.sourceRef for s in result.sources} for e in result.evidence)
    assert result.qualityScore >= 0.8
    assert sink.events[-1] == ("FINALIZE", "COMPLETED", 100)
    assert sink.saved_nodes == [
        "VALIDATE_INPUT",
        "PLANNER",
        "DECIDE_ACTION",
        "EXECUTE_TOOL",
        "EVIDENCE_BUILDER",
        "DECIDE_ACTION",
        "EXECUTE_TOOL",
        "EVIDENCE_BUILDER",
        "DECIDE_ACTION",
        "WRITER",
        "CRITIC",
        "FINALIZE",
    ]
    assert sum(1 for node, _, _ in sink.events if node == "TOOL_COMPLETED") == 2


def test_workflow_obeys_cancellation_between_nodes() -> None:
    sink = RecordingSink(cancelled=True)

    try:
        ResearchWorkflow().run(command(), sink)
    except TaskCancelled:
        pass
    else:
        raise AssertionError("cancelled task should stop")


def test_workflow_obeys_deadline_between_nodes(monkeypatch) -> None:
    calls = 0

    def clock() -> float:
        nonlocal calls
        calls += 1
        return 100.0 if calls == 1 else 131.0

    monkeypatch.setattr("xplanet_agent.workflow.time.time", clock)

    with pytest.raises(TimeoutError, match="exceeded deadline"):
        ResearchWorkflow().run(command(deadlineSeconds=30), RecordingSink())


def test_workflow_resumes_after_persisted_checkpoint_without_repeating_nodes() -> None:
    sink = RecordingSink()
    failed = False
    retry_command = command(maxSources=2, maxToolCalls=2)

    def fail_once(node: str) -> None:
        nonlocal failed
        if node == "EXECUTE_TOOL" and not failed:
            failed = True
            raise RuntimeError("simulated worker crash after durable checkpoint")

    with pytest.raises(RuntimeError, match="simulated worker crash"):
        ResearchWorkflow(after_checkpoint=fail_once).run(retry_command, sink)

    assert sink.saved_nodes == ["VALIDATE_INPUT", "PLANNER", "DECIDE_ACTION", "EXECUTE_TOOL"]
    assert [event[0] for event in sink.events] == [
        "VALIDATE_INPUT",
        "PLAN_CREATED",
        "DECIDE_ACTION",
        "TOOL_STARTED",
    ]

    result = ResearchWorkflow().run(retry_command, sink)

    assert result.qualityScore >= 0.8
    assert sink.saved_nodes.count("VALIDATE_INPUT") == 1
    assert sink.saved_nodes.count("PLANNER") == 1
    assert sink.saved_nodes.count("EXECUTE_TOOL") == retry_command.maxToolCalls
    assert sink.saved_nodes[-1] == "FINALIZE"


class RepeatingDecisionProvider(OfflineModelProvider):
    def decide(self, command, question, plan, search_hits, documents, attempted_queries, attempted_urls, tool_calls):
        return ToolAction(name="web_search", query=plan.steps[0].searchQuery, reason="repeat"), None


def test_workflow_replaces_duplicate_model_action_and_stays_within_budget() -> None:
    sink = RecordingSink()

    result = ResearchWorkflow(model_provider=RepeatingDecisionProvider()).run(
        command(maxSources=2, maxToolCalls=3), sink
    )

    assert len(result.sources) == 2
    assert sink.saved_nodes.count("EXECUTE_TOOL") == 3
    assert sink.saved_nodes.count("DECIDE_ACTION") == 4


class DuplicateSearchProvider:
    name = "duplicate-test"

    def search(self, command, action, limit):
        duplicate = SearchHit(
            url="https://example.com/one",
            title="One",
            snippet="same evidence",
        )
        return ToolExecutionResult(action=action, searchHits=[duplicate, duplicate])


def test_workflow_deduplicates_search_urls_before_persisting_sources() -> None:
    result = ResearchWorkflow(search_provider=DuplicateSearchProvider()).run(
        command(maxSources=2, maxToolCalls=1), RecordingSink()
    )

    assert [source.url for source in result.sources] == ["https://example.com/one"]
    assert len(result.evidence) == 1


class OneSupplementProvider(OfflineModelProvider):
    def __init__(self) -> None:
        self.critic_calls = 0

    def decide(self, command, question, plan, search_hits, documents, attempted_queries, attempted_urls, tool_calls):
        if tool_calls == 0:
            return ToolAction(name="web_search", query=plan.steps[0].searchQuery, reason="initial"), None
        return ToolAction(name="finish_research", reason="critic decides whether more is needed"), None

    def critic(self, command, question, claims, evidence):
        self.critic_calls += 1
        if self.critic_calls == 1:
            return CriticReview(
                approved=False,
                qualityScore=0.45,
                claimSupportScore=0.5,
                issues=[
                    CriticIssue(
                        issueType="conflicting_evidence",
                        claimId=claims[0].claimId,
                        evidenceRefs=[claims[0].evidenceRefs[0]],
                        detail="来源结论存在冲突，需要补充独立来源",
                        suggestedQuery="independent source for agent checkpoint durability",
                    )
                ],
                uncertainties=["当前结论只有单一来源。"],
                conflicts=["来源结论存在冲突，需要人工复核。"],
                supplementalQuery="independent source for agent checkpoint durability",
            ), None
        return super().critic(command, question, claims, evidence)


def test_critic_can_trigger_only_one_targeted_supplement_then_finalize() -> None:
    sink = RecordingSink()
    provider = OneSupplementProvider()

    result = ResearchWorkflow(model_provider=provider).run(
        command(maxSources=2, maxToolCalls=2), sink
    )

    assert provider.critic_calls == 2
    assert sink.saved_nodes.count("CRITIC") == 2
    assert sink.saved_nodes.count("WRITER") == 2
    assert sink.saved_nodes.count("EXECUTE_TOOL") == 2
    assert "来源结论存在冲突" in result.content
    assert sink.saved_nodes[-1] == "FINALIZE"


class UnknownCitationProvider(OfflineModelProvider):
    def write(self, command, question, plan, sources, evidence, previous_review):
        draft, usage = super().write(
            command, question, plan, sources, evidence, previous_review
        )
        draft.claims[0].evidenceRefs = ["ev-unknown"]
        draft.content += " [ev-unknown]"
        return draft, usage


def test_writer_rejects_unknown_evidence_binding() -> None:
    with pytest.raises(ValueError, match="unknown evidence citation"):
        ResearchWorkflow(model_provider=UnknownCitationProvider()).run(
            command(maxSources=1, maxToolCalls=1), RecordingSink()
        )


def test_claim_requires_at_least_one_evidence_reference() -> None:
    with pytest.raises(ValidationError):
        ClaimDraft(
            claimId="claim-1",
            statement="unsupported",
            evidenceRefs=[],
            confidence=0.1,
        )
