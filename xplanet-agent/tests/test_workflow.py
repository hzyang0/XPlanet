import pytest

from xplanet_agent.models import TaskCommand
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
    assert {c.evidenceRef for c in result.citations} == {e.evidenceRef for e in result.evidence}
    assert all(e.sourceRef in {s.sourceRef for s in result.sources} for e in result.evidence)
    assert result.qualityScore >= 0.8
    assert sink.events[-1] == ("FINALIZE", "COMPLETED", 100)
    assert sink.saved_nodes == [
        "VALIDATE_INPUT",
        "PLANNER",
        "PARALLEL_RESEARCH",
        "EVIDENCE_BUILDER",
        "WRITER",
        "CRITIC",
        "FINALIZE",
    ]


def test_workflow_obeys_cancellation_between_nodes() -> None:
    sink = RecordingSink(cancelled=True)

    try:
        ResearchWorkflow().run(command(), sink)
    except TaskCancelled:
        pass
    else:
        raise AssertionError("cancelled task should stop")


def test_workflow_resumes_after_persisted_checkpoint_without_repeating_nodes() -> None:
    sink = RecordingSink()
    failed = False

    def fail_once(node: str) -> None:
        nonlocal failed
        if node == "PARALLEL_RESEARCH" and not failed:
            failed = True
            raise RuntimeError("simulated worker crash after durable checkpoint")

    with pytest.raises(RuntimeError, match="simulated worker crash"):
        ResearchWorkflow(after_checkpoint=fail_once).run(command(), sink)

    assert sink.saved_nodes == ["VALIDATE_INPUT", "PLANNER", "PARALLEL_RESEARCH"]
    assert [event[0] for event in sink.events] == ["VALIDATE_INPUT", "PLANNER"]

    result = ResearchWorkflow().run(command(), sink)

    assert result.qualityScore >= 0.8
    assert sink.saved_nodes.count("VALIDATE_INPUT") == 1
    assert sink.saved_nodes.count("PLANNER") == 1
    assert sink.saved_nodes.count("PARALLEL_RESEARCH") == 1
    assert sink.saved_nodes[-1] == "FINALIZE"
