from xplanet_agent.models import TaskCommand
from xplanet_agent.workflow import ResearchWorkflow, TaskCancelled


class RecordingSink:
    def __init__(self, cancelled: bool = False) -> None:
        self.events: list[tuple[str, str, int]] = []
        self.cancelled = cancelled

    def emit(self, node: str, status: str, message: str, progress: int) -> None:
        self.events.append((node, status, progress))

    def is_cancelled(self) -> bool:
        return self.cancelled


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


def test_workflow_obeys_cancellation_between_nodes() -> None:
    sink = RecordingSink(cancelled=True)

    try:
        ResearchWorkflow().run(command(), sink)
    except TaskCancelled:
        pass
    else:
        raise AssertionError("cancelled task should stop")
