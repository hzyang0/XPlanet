from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from .models import (
    CitationResult,
    EvidenceResult,
    ModelUsageResult,
    ResearchResult,
    SourceResult,
    TaskCommand,
)
from .progress import NullProgressSink, ProgressSink
from .providers import ResearchProvider


class TaskCancelled(RuntimeError):
    pass


class ResearchState(TypedDict, total=False):
    command: TaskCommand
    sink: ProgressSink
    deadline_at: float
    command_hash: str
    resume_target: str
    question: str
    plan: list[str]
    sources: list[SourceResult]
    evidence: list[EvidenceResult]
    citations: list[CitationResult]
    title: str
    content: str
    quality_score: float
    revisions: int
    tool_calls: int
    needs_revision: bool
    provider_name: str
    usage: list[ModelUsageResult]


class ResearchWorkflow:
    """Bounded, traceable graph with durable application-level node checkpoints."""

    _CORPUS = (
        (
            "https://github.com/hzyang0/XPlanet",
            "XPlanet repository",
            "XPlanet uses database state machines, Transactional Outbox, RocketMQ and persistent projections "
            "to keep community writes recoverable while Caffeine and Redis serve hotspot reads.",
        ),
        (
            "https://microservices.io/patterns/data/transactional-outbox.html",
            "Transactional Outbox pattern",
            "Transactional Outbox stores the business change and an event in one database transaction, then a "
            "separate relay publishes it. Consumers must be idempotent because delivery is at least once.",
        ),
        (
            "https://docs.langchain.com/oss/python/langgraph/quickstart",
            "LangGraph quickstart",
            "LangGraph StateGraph models workflows as explicit nodes and edges, including conditional routing. "
            "This makes agent steps observable and gives recovery work a concrete state boundary.",
        ),
        (
            "https://redis.io/docs/latest/develop/data-types/streams/",
            "Redis Streams documentation",
            "Redis Streams provide an append-only event structure with IDs and bounded reads, which fits transient "
            "progress delivery while durable task status remains in a database.",
        ),
    )

    def __init__(
        self,
        provider: ResearchProvider | None = None,
        after_checkpoint: Callable[[str], None] | None = None,
    ) -> None:
        self._provider = provider
        self._after_checkpoint = after_checkpoint
        builder = StateGraph(ResearchState)
        builder.add_node("resume", self._resume)
        builder.add_node("validate_input", self._validate_input)
        builder.add_node("planner", self._planner)
        builder.add_node("research", self._research)
        builder.add_node("evidence_builder", self._evidence_builder)
        builder.add_node("writer", self._writer)
        builder.add_node("critic", self._critic)
        builder.add_node("finalize", self._finalize)
        builder.add_edge(START, "resume")
        builder.add_conditional_edges("resume", self._resume_route)
        builder.add_edge("validate_input", "planner")
        builder.add_edge("planner", "research")
        builder.add_edge("research", "evidence_builder")
        builder.add_edge("evidence_builder", "writer")
        builder.add_edge("writer", "critic")
        builder.add_conditional_edges("critic", self._after_critic)
        builder.add_edge("finalize", END)
        self._graph = builder.compile()

    def run(self, command: TaskCommand, sink: ProgressSink | None = None) -> ResearchResult:
        active_sink = sink or NullProgressSink()
        command_hash = self._command_hash(command)
        state: ResearchState = {
            "command": command,
            "sink": active_sink,
            "deadline_at": time.time() + command.deadlineSeconds,
            "command_hash": command_hash,
            "resume_target": "validate_input",
            "revisions": 0,
            "tool_calls": 0,
            "needs_revision": False,
            "provider_name": self.provider_name,
            "usage": [],
        }
        saved = active_sink.load_checkpoint()
        if saved:
            state.update(self._restore_checkpoint(saved, command_hash))

        final = self._graph.invoke(state)
        return ResearchResult(
            taskId=command.taskId,
            runId=command.runId,
            title=final["title"],
            content=final["content"],
            qualityScore=final["quality_score"],
            provider=final["provider_name"],
            sources=final["sources"],
            evidence=final["evidence"],
            citations=final["citations"],
            usage=final.get("usage", []),
        )

    @property
    def provider_name(self) -> str:
        return self._provider.name if self._provider is not None else "offline-demo"

    def _resume(self, state: ResearchState) -> ResearchState:
        self._guard(state)
        return {}

    def _resume_route(
        self, state: ResearchState
    ) -> Literal[
        "validate_input",
        "planner",
        "research",
        "evidence_builder",
        "writer",
        "critic",
        "finalize",
        "__end__",
    ]:
        target = state.get("resume_target", "validate_input")
        allowed = {
            "validate_input",
            "planner",
            "research",
            "evidence_builder",
            "writer",
            "critic",
            "finalize",
            END,
        }
        if target not in allowed:
            raise ValueError(f"unsupported checkpoint resume target: {target}")
        return target  # type: ignore[return-value]

    def _guard(self, state: ResearchState) -> None:
        command = state["command"]
        if state["sink"].is_cancelled():
            raise TaskCancelled(f"task {command.taskId} was cancelled")
        if time.time() > state["deadline_at"]:
            raise TimeoutError(f"task {command.taskId} exceeded deadline")

    def _validate_input(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        question = re.sub(r"\s+", " ", state["command"].question).strip()
        if not question or len(question) > 2000:
            raise ValueError("question must contain 1..2000 characters")
        updates: ResearchState = {"question": question}
        self._checkpoint(state, "VALIDATE_INPUT", updates, "planner", started)
        state["sink"].emit("VALIDATE_INPUT", "COMPLETED", "输入与预算校验完成", 10)
        return updates

    def _planner(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        question = state["question"]
        plan = [
            f"明确问题边界：{question}",
            "查找能够支撑关键结论的来源并保留来源身份",
            "比较可靠性、复杂度与适用边界后形成可审核结论",
        ]
        updates: ResearchState = {"plan": plan}
        self._checkpoint(state, "PLANNER", updates, "research", started)
        state["sink"].emit("PLANNER", "COMPLETED", f"生成 {len(plan)} 个研究步骤", 25)
        return updates

    def _research(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        command = state["command"]
        if self._provider is not None:
            result = self._provider.research(command)
            updates: ResearchState = {
                "sources": result.sources,
                "evidence": result.evidence,
                "citations": result.citations,
                "title": result.title,
                "content": result.content,
                "tool_calls": result.tool_calls,
                "provider_name": self._provider.name,
                "usage": result.usage,
            }
            self._checkpoint(state, "PARALLEL_RESEARCH", updates, "evidence_builder", started)
            state["sink"].emit(
                "PARALLEL_RESEARCH",
                "COMPLETED",
                f"联网研究返回 {len(result.sources)} 个带引用来源",
                45,
            )
            return updates
        count = min(command.maxSources, command.maxToolCalls, len(self._CORPUS))
        if count < 1:
            raise ValueError("research budget does not allow any source")
        retrieved_at = datetime.now(timezone.utc).isoformat()
        sources = []
        for index, (url, title, content) in enumerate(self._CORPUS[:count], start=1):
            sources.append(
                SourceResult(
                    sourceRef=f"src-{index}",
                    url=url,
                    title=title,
                    contentHash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
                    retrievedAt=retrieved_at,
                    metadataJson='{"provider":"offline-demo"}',
                )
            )
        updates: ResearchState = {"sources": sources, "tool_calls": count}
        self._checkpoint(state, "PARALLEL_RESEARCH", updates, "evidence_builder", started)
        state["sink"].emit("PARALLEL_RESEARCH", "COMPLETED", f"检索并读取 {count} 个来源", 45)
        return updates

    def _evidence_builder(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        if state.get("evidence"):
            evidence = state["evidence"]
            updates: ResearchState = {}
            self._checkpoint(state, "EVIDENCE_BUILDER", updates, "writer", started)
            state["sink"].emit(
                "EVIDENCE_BUILDER",
                "COMPLETED",
                f"校验 {len(evidence)} 条联网引用上下文",
                62,
            )
            return updates
        corpus_by_url = {url: content for url, _, content in self._CORPUS}
        evidence = []
        for index, source in enumerate(state["sources"], start=1):
            evidence.append(
                EvidenceResult(
                    evidenceRef=f"ev-{index}",
                    sourceRef=source.sourceRef,
                    locator="offline corpus summary",
                    content=corpus_by_url[source.url],
                    score=max(0.75, 0.96 - index * 0.03),
                )
            )
        updates: ResearchState = {"evidence": evidence}
        self._checkpoint(state, "EVIDENCE_BUILDER", updates, "writer", started)
        state["sink"].emit("EVIDENCE_BUILDER", "COMPLETED", f"绑定 {len(evidence)} 条证据", 62)
        return updates

    def _writer(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        if state.get("content") and state.get("title") and state.get("citations"):
            updates: ResearchState = {}
            self._checkpoint(state, "WRITER", updates, "critic", started)
            state["sink"].emit("WRITER", "COMPLETED", "保留模型生成的带引用报告", 80)
            return updates
        question = state["question"]
        evidence = state["evidence"]
        sources = state["sources"]
        citations = [
            CitationResult(
                claimId=f"claim-{index}",
                evidenceRef=item.evidenceRef,
                supportScore=item.score,
            )
            for index, item in enumerate(evidence, start=1)
        ]
        findings = "\n".join(f"- {item.content} [{item.evidenceRef}]" for item in evidence)
        source_list = "\n".join(
            f"- [{source.title}]({source.url}) ({source.sourceRef})" for source in sources
        )
        content = (
            f"# {question}\n\n"
            "> 当前报告由离线确定性提供器生成，用于验证 Agent 编排、证据和审核链路；"
            "不代表已完成实时互联网研究。\n\n"
            "## 研究计划\n\n"
            + "\n".join(f"{i}. {step}" for i, step in enumerate(state["plan"], start=1))
            + "\n\n## 有证据支持的发现\n\n"
            + findings
            + "\n\n## 工程结论\n\n"
            "可靠的长任务系统需要把事实状态、可靠命令、幂等处理与可丢失的实时进度分层。"
            "XPlanet 当前用 MySQL 保存任务事实，用 Outbox + RocketMQ 排队，用 Redis Stream 传进度，"
            "并要求最终报告中的引用只能指向已保存的 evidenceRef。\n\n"
            "## 来源\n\n"
            + source_list
        )
        updates: ResearchState = {
            "title": f"研究报告：{question[:80]}",
            "content": content,
            "citations": citations,
        }
        self._checkpoint(state, "WRITER", updates, "critic", started)
        state["sink"].emit("WRITER", "COMPLETED", "生成带 evidenceRef 的报告草稿", 80)
        return updates

    def _critic(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        evidence_ids = {item.evidenceRef for item in state["evidence"]}
        citation_ids = {item.evidenceRef for item in state["citations"]}
        coverage = len(citation_ids & evidence_ids) / max(1, len(evidence_ids))
        valid = citation_ids.issubset(evidence_ids)
        score = min(1.0, 0.65 + coverage * 0.3 + (0.05 if valid else 0.0))
        should_revise = score < 0.8 and state.get("revisions", 0) < 1
        updates: ResearchState = {
            "quality_score": score,
            "needs_revision": should_revise,
            "revisions": state.get("revisions", 0) + (1 if should_revise else 0),
        }
        next_node = "writer" if should_revise else "finalize"
        self._checkpoint(state, "CRITIC", updates, next_node, started)
        state["sink"].emit(
            "CRITIC",
            "COMPLETED",
            f"引用索引有效，覆盖率 {coverage:.0%}",
            95,
        )
        return updates

    def _after_critic(self, state: ResearchState) -> Literal["writer", "finalize"]:
        return "writer" if state.get("needs_revision", False) else "finalize"

    def _finalize(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        self._checkpoint(state, "FINALIZE", {}, END, started)
        state["sink"].emit("FINALIZE", "COMPLETED", "报告进入人工审核", 100)
        return {}

    def _checkpoint(
        self,
        state: ResearchState,
        node: str,
        updates: ResearchState,
        next_node: str,
        started: float,
    ) -> None:
        merged: ResearchState = {**state, **updates}
        input_payload = self._checkpoint_payload(state, node, next_node)
        input_hash = hashlib.sha256(input_payload.encode("utf-8")).hexdigest()
        state_json = self._checkpoint_payload(merged, node, next_node)
        duration_ms = max(0, int((time.monotonic() - started) * 1000))
        state["sink"].save_checkpoint(node, input_hash, state_json, duration_ms)
        if self._after_checkpoint is not None:
            self._after_checkpoint(node)

    def _checkpoint_payload(
        self,
        state: ResearchState,
        completed_node: str,
        next_node: str,
    ) -> str:
        payload: dict[str, Any] = {
            "schemaVersion": 1,
            "commandHash": state["command_hash"],
            "completedNode": completed_node,
            "nextNode": next_node,
            "deadlineAt": state["deadline_at"],
            "question": state.get("question"),
            "plan": state.get("plan", []),
            "sources": [item.model_dump() for item in state.get("sources", [])],
            "evidence": [item.model_dump() for item in state.get("evidence", [])],
            "citations": [item.model_dump() for item in state.get("citations", [])],
            "title": state.get("title"),
            "content": state.get("content"),
            "qualityScore": state.get("quality_score"),
            "revisions": state.get("revisions", 0),
            "toolCalls": state.get("tool_calls", 0),
            "needsRevision": state.get("needs_revision", False),
            "providerName": state.get("provider_name", "offline-demo"),
            "usage": [item.model_dump() for item in state.get("usage", [])],
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)

    def _restore_checkpoint(self, raw: str, command_hash: str) -> ResearchState:
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError("checkpoint is not valid JSON") from exc
        if payload.get("schemaVersion") != 1:
            raise ValueError("unsupported checkpoint schema version")
        if payload.get("commandHash") != command_hash:
            raise ValueError("checkpoint does not belong to this task command")
        target = payload.get("nextNode")
        if not isinstance(target, str):
            raise ValueError("checkpoint has no resume target")
        restored: ResearchState = {
            "resume_target": target,
            "deadline_at": float(payload["deadlineAt"]),
            "command_hash": command_hash,
            "plan": list(payload.get("plan") or []),
            "sources": [SourceResult.model_validate(item) for item in payload.get("sources") or []],
            "evidence": [
                EvidenceResult.model_validate(item) for item in payload.get("evidence") or []
            ],
            "citations": [
                CitationResult.model_validate(item) for item in payload.get("citations") or []
            ],
            "revisions": int(payload.get("revisions") or 0),
            "tool_calls": int(payload.get("toolCalls") or 0),
            "needs_revision": bool(payload.get("needsRevision")),
            "provider_name": payload.get("providerName") or "offline-demo",
            "usage": [ModelUsageResult.model_validate(item) for item in payload.get("usage") or []],
        }
        optional_fields = {
            "question": "question",
            "title": "title",
            "content": "content",
            "qualityScore": "quality_score",
        }
        for source_name, state_name in optional_fields.items():
            value = payload.get(source_name)
            if value is not None:
                restored[state_name] = value  # type: ignore[literal-required]
        return restored

    def _command_hash(self, command: TaskCommand) -> str:
        canonical = json.dumps(
            command.model_dump(exclude={"eventId", "occurredAt", "traceId"}),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
