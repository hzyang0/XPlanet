from __future__ import annotations

import hashlib
import re
import time
from datetime import datetime, timezone
from typing import Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from .models import (
    CitationResult,
    EvidenceResult,
    ResearchResult,
    SourceResult,
    TaskCommand,
)
from .progress import NullProgressSink, ProgressSink


class TaskCancelled(RuntimeError):
    pass


class ResearchState(TypedDict, total=False):
    command: TaskCommand
    sink: ProgressSink
    started_at: float
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


class ResearchWorkflow:
    """Bounded, traceable graph with an offline provider for deterministic demos and tests."""

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

    def __init__(self) -> None:
        builder = StateGraph(ResearchState)
        builder.add_node("validate_input", self._validate_input)
        builder.add_node("planner", self._planner)
        builder.add_node("research", self._research)
        builder.add_node("evidence_builder", self._evidence_builder)
        builder.add_node("writer", self._writer)
        builder.add_node("critic", self._critic)
        builder.add_edge(START, "validate_input")
        builder.add_edge("validate_input", "planner")
        builder.add_edge("planner", "research")
        builder.add_edge("research", "evidence_builder")
        builder.add_edge("evidence_builder", "writer")
        builder.add_edge("writer", "critic")
        builder.add_conditional_edges("critic", self._after_critic)
        self._graph = builder.compile()

    def run(self, command: TaskCommand, sink: ProgressSink | None = None) -> ResearchResult:
        final = self._graph.invoke(
            {
                "command": command,
                "sink": sink or NullProgressSink(),
                "started_at": time.monotonic(),
                "revisions": 0,
                "tool_calls": 0,
            }
        )
        return ResearchResult(
            taskId=command.taskId,
            runId=command.runId,
            title=final["title"],
            content=final["content"],
            qualityScore=final["quality_score"],
            provider="offline-demo",
            sources=final["sources"],
            evidence=final["evidence"],
            citations=final["citations"],
        )

    def _guard(self, state: ResearchState) -> None:
        command = state["command"]
        if state["sink"].is_cancelled():
            raise TaskCancelled(f"task {command.taskId} was cancelled")
        if time.monotonic() - state["started_at"] > command.deadlineSeconds:
            raise TimeoutError(f"task {command.taskId} exceeded deadline")

    def _validate_input(self, state: ResearchState) -> ResearchState:
        self._guard(state)
        question = re.sub(r"\s+", " ", state["command"].question).strip()
        if not question or len(question) > 2000:
            raise ValueError("question must contain 1..2000 characters")
        state["sink"].emit("VALIDATE_INPUT", "COMPLETED", "输入与预算校验完成", 10)
        return {"question": question}

    def _planner(self, state: ResearchState) -> ResearchState:
        self._guard(state)
        question = state["question"]
        plan = [
            f"明确问题边界：{question}",
            "查找能够支撑关键结论的来源并保留来源身份",
            "比较可靠性、复杂度与适用边界后形成可审核结论",
        ]
        state["sink"].emit("PLANNER", "COMPLETED", f"生成 {len(plan)} 个研究步骤", 25)
        return {"plan": plan}

    def _research(self, state: ResearchState) -> ResearchState:
        self._guard(state)
        command = state["command"]
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
        state["sink"].emit("PARALLEL_RESEARCH", "COMPLETED", f"检索并读取 {count} 个来源", 45)
        return {"sources": sources, "tool_calls": count}

    def _evidence_builder(self, state: ResearchState) -> ResearchState:
        self._guard(state)
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
        state["sink"].emit("EVIDENCE_BUILDER", "COMPLETED", f"绑定 {len(evidence)} 条证据", 62)
        return {"evidence": evidence}

    def _writer(self, state: ResearchState) -> ResearchState:
        self._guard(state)
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
        findings = "\n".join(
            f"- {item.content} [{item.evidenceRef}]" for item in evidence
        )
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
        state["sink"].emit("WRITER", "COMPLETED", "生成带 evidenceRef 的报告草稿", 80)
        return {
            "title": f"研究报告：{question[:80]}",
            "content": content,
            "citations": citations,
        }

    def _critic(self, state: ResearchState) -> ResearchState:
        self._guard(state)
        evidence_ids = {item.evidenceRef for item in state["evidence"]}
        citation_ids = {item.evidenceRef for item in state["citations"]}
        coverage = len(citation_ids & evidence_ids) / max(1, len(evidence_ids))
        valid = citation_ids.issubset(evidence_ids)
        score = min(1.0, 0.65 + coverage * 0.3 + (0.05 if valid else 0.0))
        state["sink"].emit("CRITIC", "COMPLETED", f"引用索引有效，覆盖率 {coverage:.0%}", 95)
        return {"quality_score": score}

    def _after_critic(self, state: ResearchState) -> Literal["writer", END]:
        if state["quality_score"] < 0.8 and state.get("revisions", 0) < 1:
            state["revisions"] = state.get("revisions", 0) + 1
            return "writer"
        state["sink"].emit("FINALIZE", "COMPLETED", "报告进入人工审核", 100)
        return END
