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
    ClaimDraft,
    CriticReview,
    EvidenceResult,
    FetchedDocument,
    ModelUsageResult,
    ResearchPlan,
    ResearchResult,
    SearchHit,
    SourceResult,
    TaskCommand,
    ToolAction,
    ToolExecutionResult,
)
from .progress import NullProgressSink, ProgressSink
from .providers import ModelProvider, OfflineModelProvider, OfflineSearchProvider, SearchProvider
from .quality import lexical_support_score
from .tools import (
    DocumentFetcher,
    InternalSearchProvider,
    OfflineDocumentFetcher,
    OfflineInternalSearchProvider,
    ToolRegistry,
)


class TaskCancelled(RuntimeError):
    pass


class ResearchState(TypedDict, total=False):
    command: TaskCommand
    sink: ProgressSink
    deadline_at: float
    command_hash: str
    resume_target: str
    question: str
    plan: ResearchPlan
    search_hits: list[SearchHit]
    documents: list[FetchedDocument]
    attempted_queries: list[str]
    attempted_internal_queries: list[str]
    attempted_urls: list[str]
    action: ToolAction
    tool_result: ToolExecutionResult | None
    decision_count: int
    research_complete: bool
    sources: list[SourceResult]
    evidence: list[EvidenceResult]
    claims: list[ClaimDraft]
    citations: list[CitationResult]
    critic_review: CriticReview
    title: str
    content: str
    quality_score: float
    revisions: int
    tool_calls: int
    needs_revision: bool
    critic_next: str
    evidence_next: str
    supplemental_count: int
    supplemental_pending: bool
    provider_name: str
    usage: list[ModelUsageResult]


class ResearchWorkflow:
    """One bounded Agent loop with durable checkpoints around every side effect."""

    def __init__(
        self,
        model_provider: ModelProvider | None = None,
        search_provider: SearchProvider | None = None,
        document_fetcher: DocumentFetcher | None = None,
        internal_search_provider: InternalSearchProvider | None = None,
        after_checkpoint: Callable[[str], None] | None = None,
    ) -> None:
        self._model = model_provider or OfflineModelProvider()
        self._tools = ToolRegistry(
            search_provider or OfflineSearchProvider(),
            document_fetcher or OfflineDocumentFetcher(),
            internal_search_provider or OfflineInternalSearchProvider(),
        )
        self._after_checkpoint = after_checkpoint

        builder = StateGraph(ResearchState)
        builder.add_node("resume", self._resume)
        builder.add_node("validate_input", self._validate_input)
        builder.add_node("planner", self._planner)
        builder.add_node("decide_action", self._decide_action)
        builder.add_node("execute_tool", self._execute_tool)
        builder.add_node("evidence_builder", self._evidence_builder)
        builder.add_node("writer", self._writer)
        builder.add_node("critic", self._critic)
        builder.add_node("finalize", self._finalize)
        builder.add_edge(START, "resume")
        builder.add_conditional_edges("resume", self._resume_route)
        builder.add_edge("validate_input", "planner")
        builder.add_edge("planner", "decide_action")
        builder.add_conditional_edges("decide_action", self._after_decision)
        builder.add_edge("execute_tool", "evidence_builder")
        builder.add_conditional_edges("evidence_builder", self._after_evidence)
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
            "search_hits": [],
            "documents": [],
            "attempted_queries": [],
            "attempted_internal_queries": [],
            "attempted_urls": [],
            "decision_count": 0,
            "research_complete": False,
            "sources": [],
            "evidence": [],
            "claims": [],
            "citations": [],
            "revisions": 0,
            "tool_calls": 0,
            "needs_revision": False,
            "critic_next": "finalize",
            "evidence_next": "decide_action",
            "supplemental_count": 0,
            "supplemental_pending": False,
            "provider_name": self.provider_name,
            "usage": [],
        }
        saved = active_sink.load_checkpoint()
        if saved:
            state.update(self._restore_checkpoint(saved, command_hash))

        final = self._graph.invoke(state, config={"recursion_limit": command.maxToolCalls * 3 + 20})
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
        return self._model.name

    def _resume(self, state: ResearchState) -> ResearchState:
        self._guard(state)
        return {}

    def _resume_route(
        self, state: ResearchState
    ) -> Literal[
        "validate_input",
        "planner",
        "decide_action",
        "execute_tool",
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
            "decide_action",
            "execute_tool",
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
        state["sink"].emit("VALIDATE_INPUT", "COMPLETED", "输入与预算校验完成", 8)
        return updates

    def _planner(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        plan, usage = self._model.plan(
            self._command_with_remaining_tokens(state),
            state["question"],
        )
        updates: ResearchState = {"plan": plan}
        if usage is not None:
            updates["usage"] = self._append_usage(state, usage)
        self._checkpoint(state, "PLANNER", updates, "decide_action", started)
        state["sink"].emit("PLAN_CREATED", "COMPLETED", f"生成 {len(plan.steps)} 个研究步骤", 18)
        return updates

    def _decide_action(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        command = state["command"]
        decision_count = state.get("decision_count", 0) + 1
        forced_finish = (
            state.get("tool_calls", 0) >= command.maxToolCalls
            or decision_count > command.maxToolCalls + 2
        )
        usage = None
        if forced_finish:
            action = ToolAction(name="finish_research", reason="工具或决策预算已耗尽")
        else:
            action, usage = self._model.decide(
                self._command_with_remaining_tokens(state),
                state["question"],
                state["plan"],
                state.get("search_hits", []),
                state.get("documents", []),
                state.get("attempted_queries", []),
                state.get("attempted_internal_queries", []),
                state.get("attempted_urls", []),
                state.get("tool_calls", 0),
            )
            action = self._sanitize_action(state, action)

        complete = action.name == "finish_research"
        if complete and not state.get("evidence"):
            fallback = self._fallback_action(state)
            if fallback.name != "finish_research" and state.get("tool_calls", 0) < command.maxToolCalls:
                action = fallback
                complete = False
        updates: ResearchState = {
            "action": action,
            "decision_count": decision_count,
            "research_complete": complete,
        }
        if usage is not None:
            updates["usage"] = self._append_usage(state, usage)
        next_node = "writer" if complete else "execute_tool"
        self._checkpoint(state, "DECIDE_ACTION", updates, next_node, started)
        message = (
            "证据收集完成，进入报告生成"
            if complete
            else f"选择工具 {action.name}：{action.reason}"
        )
        state["sink"].emit("DECIDE_ACTION", "COMPLETED", message, self._loop_progress(state, 0))
        return updates

    def _sanitize_action(self, state: ResearchState, action: ToolAction) -> ToolAction:
        if action.name == "web_search":
            query = (action.query or "").strip()
            if query in state.get("attempted_queries", []):
                return self._fallback_action(state)
            return action.model_copy(update={"query": query})
        if action.name == "web_fetch":
            url = (action.url or "").strip()
            known_urls = {item.url for item in state.get("search_hits", [])}
            if url in state.get("attempted_urls", []) or url not in known_urls:
                return self._fallback_action(state)
            return action.model_copy(update={"url": url})
        if action.name == "internal_search":
            query = (action.query or "").strip()
            if query in state.get("attempted_internal_queries", []):
                return self._fallback_action(state)
            return action.model_copy(update={"query": query})
        return action

    def _fallback_action(self, state: ResearchState) -> ToolAction:
        attempted_urls = set(state.get("attempted_urls", []))
        fetched_urls = {item.url for item in state.get("documents", [])}
        for hit in state.get("search_hits", [])[: state["command"].maxSources]:
            if (
                hit.sourceType == "web"
                and hit.url not in attempted_urls
                and hit.url not in fetched_urls
            ):
                return ToolAction(name="web_fetch", url=hit.url, reason="去重后读取下一个候选来源")
        question = state["question"]
        if question not in state.get("attempted_internal_queries", []):
            return ToolAction(
                name="internal_search",
                query=question,
                reason="去重后查询站内已发布知识",
            )
        attempted_queries = set(state.get("attempted_queries", []))
        for step in state["plan"].steps:
            if step.searchQuery not in attempted_queries:
                return ToolAction(name="web_search", query=step.searchQuery, reason="去重后执行下一研究步骤")
        return ToolAction(name="finish_research", reason="没有未执行的有效工具动作")

    def _after_decision(self, state: ResearchState) -> Literal["execute_tool", "writer"]:
        return "writer" if state.get("research_complete", False) else "execute_tool"

    def _execute_tool(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        action = state["action"]
        if state.get("tool_calls", 0) >= state["command"].maxToolCalls:
            raise ValueError("tool-call budget exhausted before tool execution")
        state["sink"].emit(
            "TOOL_STARTED",
            "RUNNING",
            f"开始执行 {action.name}",
            self._loop_progress(state, 1),
        )
        result = self._tools.execute(self._command_with_remaining_tokens(state), action)
        attempted_queries = list(state.get("attempted_queries", []))
        attempted_internal_queries = list(state.get("attempted_internal_queries", []))
        attempted_urls = list(state.get("attempted_urls", []))
        if action.name == "web_search" and action.query not in attempted_queries:
            attempted_queries.append(action.query or "")
        if action.name == "web_fetch" and action.url not in attempted_urls:
            attempted_urls.append(action.url or "")
        if action.name == "internal_search" and action.query not in attempted_internal_queries:
            attempted_internal_queries.append(action.query or "")
        usage = list(state.get("usage", []))
        for item in result.usage:
            usage = self._append_usage({**state, "usage": usage}, item)
        updates: ResearchState = {
            "tool_result": result,
            "tool_calls": state.get("tool_calls", 0) + 1,
            "attempted_queries": attempted_queries,
            "attempted_internal_queries": attempted_internal_queries,
            "attempted_urls": attempted_urls,
            "usage": usage,
        }
        self._checkpoint(state, "EXECUTE_TOOL", updates, "evidence_builder", started)
        count = len(result.searchHits) if result.searchHits else 1
        state["sink"].emit(
            "TOOL_COMPLETED",
            "COMPLETED",
            f"{action.name} 返回 {count} 项结果",
            self._loop_progress({**state, **updates}, 2),
        )
        return updates

    def _evidence_builder(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        result = state.get("tool_result")
        if result is None:
            raise ValueError("evidence builder has no tool result")
        search_hits = self._merge_hits(state.get("search_hits", []), result.searchHits)
        if result.document is not None and result.action.url != result.document.url:
            search_hits = [
                item.model_copy(
                    update={
                        "url": result.document.url,
                        "title": result.document.title,
                        "snippet": result.document.content[:4000],
                    }
                )
                if item.url == result.action.url
                else item
                for item in search_hits
            ]
        documents = self._merge_documents(
            state.get("documents", []),
            [result.document] if result.document is not None else [],
        )
        sources, evidence = self._materialize_evidence(state["command"], search_hits, documents)
        if not evidence and result.action.name != "internal_search":
            raise ValueError("tool result did not produce usable evidence")
        next_node = "writer" if state.get("supplemental_pending", False) else "decide_action"
        updates: ResearchState = {
            "search_hits": search_hits,
            "documents": documents,
            "sources": sources,
            "evidence": evidence,
            "tool_result": None,
            "supplemental_pending": False,
            "evidence_next": next_node,
        }
        self._checkpoint(state, "EVIDENCE_BUILDER", updates, next_node, started)
        state["sink"].emit(
            "EVIDENCE_ADDED",
            "COMPLETED",
            f"当前保留 {len(sources)} 个来源、{len(evidence)} 条证据",
            self._loop_progress(state, 3),
        )
        return updates

    @staticmethod
    def _after_evidence(state: ResearchState) -> Literal["decide_action", "writer"]:
        return "writer" if state.get("evidence_next") == "writer" else "decide_action"

    @staticmethod
    def _merge_hits(existing: list[SearchHit], additions: list[SearchHit]) -> list[SearchHit]:
        merged = []
        seen = set()
        for item in [*existing, *additions]:
            if item.url in seen:
                continue
            seen.add(item.url)
            merged.append(item)
        return merged[:60]

    @staticmethod
    def _merge_documents(
        existing: list[FetchedDocument], additions: list[FetchedDocument]
    ) -> list[FetchedDocument]:
        merged = {item.url: item for item in existing}
        for item in additions:
            merged[item.url] = item
        return list(merged.values())[:20]

    @staticmethod
    def _materialize_evidence(
        command: TaskCommand,
        hits: list[SearchHit],
        documents: list[FetchedDocument],
    ) -> tuple[list[SourceResult], list[EvidenceResult]]:
        candidates = list(hits)
        known_urls = {item.url for item in candidates}
        for document in documents:
            if document.url not in known_urls:
                candidates.append(
                    SearchHit(url=document.url, title=document.title, snippet=document.content[:4000])
                )
                known_urls.add(document.url)
        documents_by_url = {item.url: item for item in documents}
        retrieved_at = datetime.now(timezone.utc).isoformat()
        sources = []
        evidence = []
        content_hashes = set()
        for candidate in candidates:
            document = documents_by_url.get(candidate.url)
            content = document.content if document else candidate.snippet
            content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
            if content_hash in content_hashes:
                continue
            content_hashes.add(content_hash)
            index = len(sources) + 1
            source_ref = f"src-{index}"
            evidence_ref = f"ev-{index}"
            metadata = json.dumps(
                {
                    "evidenceType": (
                        "fetched-document"
                        if document
                        else "internal-article"
                        if candidate.sourceType == "internal"
                        else "offline-corpus"
                        if candidate.sourceType == "offline"
                        else "search-snippet"
                    ),
                    "semanticSupportVerified": bool(document),
                },
                ensure_ascii=False,
                separators=(",", ":"),
            )
            sources.append(
                SourceResult(
                    sourceRef=source_ref,
                    url=document.url if document else candidate.url,
                    title=document.title if document else candidate.title,
                    contentHash=content_hash,
                    retrievedAt=retrieved_at,
                    metadataJson=metadata,
                )
            )
            evidence.append(
                EvidenceResult(
                    evidenceRef=evidence_ref,
                    sourceRef=source_ref,
                    locator=(
                        "fetched document"
                        if document
                        else "published internal article"
                        if candidate.sourceType == "internal"
                        else "offline corpus"
                        if candidate.sourceType == "offline"
                        else "web search snippet"
                    ),
                    content=content[:4000],
                    contentHash=hashlib.sha256(content[:4000].encode("utf-8")).hexdigest(),
                    score=(
                        0.88
                        if document
                        else 0.8
                        if candidate.sourceType in {"internal", "offline"}
                        else 0.55
                    ),
                )
            )
            if len(sources) >= command.maxSources:
                break
        return sources, evidence

    def _writer(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        if not state.get("sources") or not state.get("evidence"):
            raise ValueError("cannot write a report without persisted evidence")
        draft, usage = self._model.write(
            self._command_with_remaining_tokens(state),
            state["question"],
            state["plan"],
            state["sources"],
            state["evidence"],
            state.get("critic_review"),
        )
        citations = self._validate_claims_and_build_citations(
            draft.claims,
            state["evidence"],
            draft.content,
        )
        updates: ResearchState = {
            "title": draft.title,
            "content": draft.content,
            "claims": draft.claims,
            "citations": citations,
        }
        if usage is not None:
            updates["usage"] = self._append_usage(state, usage)
        self._checkpoint(state, "WRITER", updates, "critic", started)
        state["sink"].emit(
            "WRITER",
            "COMPLETED",
            f"生成 {len(draft.claims)} 个显式 Claim 及其证据绑定",
            86,
        )
        return updates

    @staticmethod
    def _validate_claims_and_build_citations(
        claims: list[ClaimDraft],
        evidence: list[EvidenceResult],
        content: str,
    ) -> list[CitationResult]:
        evidence_by_ref = {item.evidenceRef: item for item in evidence}
        claim_ids = set()
        citations = []
        for claim in claims:
            if claim.claimId in claim_ids:
                raise ValueError("writer returned a duplicate claim identity")
            claim_ids.add(claim.claimId)
            if claim.claimId not in content:
                raise ValueError("writer report does not expose its claim identity")
            for evidence_ref in dict.fromkeys(claim.evidenceRefs):
                item = evidence_by_ref.get(evidence_ref)
                if item is None:
                    raise ValueError("writer returned an unknown evidence citation")
                if evidence_ref not in content:
                    raise ValueError("writer report does not expose its evidence citation")
                citations.append(
                    CitationResult(
                        claimId=claim.claimId,
                        evidenceRef=evidence_ref,
                        supportScore=lexical_support_score(claim.statement, item.content),
                    )
                )
        if not citations:
            raise ValueError("writer returned no claim-evidence citation")
        return citations

    def _critic(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        review, usage = self._model.critic(
            self._command_with_remaining_tokens(state),
            state["question"],
            state["claims"],
            state["evidence"],
        )
        self._validate_critic_review(review, state["claims"], state["evidence"])
        score = round(
            min(review.qualityScore, 0.4 * review.qualityScore + 0.6 * review.claimSupportScore),
            4,
        )
        needs_repair = not review.approved or bool(review.issues)
        query = (review.supplementalQuery or "").strip()
        can_supplement = (
            needs_repair
            and bool(query)
            and state.get("supplemental_count", 0) < 1
            and state.get("tool_calls", 0) < state["command"].maxToolCalls
            and query not in state.get("attempted_queries", [])
        )
        can_rewrite = needs_repair and state.get("revisions", 0) < 1
        next_node = "finalize"
        updates: ResearchState = {
            "quality_score": score,
            "critic_review": review,
            "needs_revision": False,
            "critic_next": "finalize",
        }
        if usage is not None:
            updates["usage"] = self._append_usage(state, usage)
        if can_supplement:
            updates.update(
                {
                    "action": ToolAction(
                        name="web_search",
                        query=query,
                        reason="Critic 发现关键论点证据不足，执行唯一一次定向补研究",
                    ),
                    "supplemental_count": state.get("supplemental_count", 0) + 1,
                    "supplemental_pending": True,
                    "revisions": state.get("revisions", 0) + 1,
                    "needs_revision": True,
                    "critic_next": "execute_tool",
                }
            )
            next_node = "execute_tool"
        elif can_rewrite:
            updates.update(
                {
                    "revisions": state.get("revisions", 0) + 1,
                    "needs_revision": True,
                    "critic_next": "writer",
                }
            )
            next_node = "writer"
        else:
            updates["content"] = self._append_critic_disclosure(state["content"], review)
        self._checkpoint(state, "CRITIC", updates, next_node, started)
        state["sink"].emit(
            "CRITIC",
            "COMPLETED",
            f"Claim 支持率 {review.claimSupportScore:.0%}，问题 {len(review.issues)} 项，下一步 {next_node}",
            95 if next_node == "finalize" else 88,
        )
        return updates

    @staticmethod
    def _validate_critic_review(
        review: CriticReview,
        claims: list[ClaimDraft],
        evidence: list[EvidenceResult],
    ) -> None:
        claim_ids = {item.claimId for item in claims}
        evidence_ids = {item.evidenceRef for item in evidence}
        for issue in review.issues:
            if issue.claimId is not None and issue.claimId not in claim_ids:
                raise ValueError("critic referenced an unknown claim")
            if not set(issue.evidenceRefs).issubset(evidence_ids):
                raise ValueError("critic referenced unknown evidence")

    @staticmethod
    def _append_critic_disclosure(content: str, review: CriticReview) -> str:
        uncertainty = review.uncertainties or ["未发现需要额外披露的不确定项。"]
        conflicts = review.conflicts or ["未发现来源间的直接冲突。"]
        unresolved = [item.detail for item in review.issues] or ["无未解决的结构化 Critic 问题。"]
        return (
            content.rstrip()
            + "\n\n## Critic 质量审计\n\n"
            + f"- Claim 支持率：{review.claimSupportScore:.0%}\n"
            + f"- 综合质量分：{review.qualityScore:.2f}\n"
            + "- 不确定项："
            + "；".join(uncertainty)
            + "\n- 冲突："
            + "；".join(conflicts)
            + "\n- 未解决问题："
            + "；".join(unresolved)
        )

    def _after_critic(
        self, state: ResearchState
    ) -> Literal["execute_tool", "writer", "finalize"]:
        target = state.get("critic_next", "finalize")
        if target not in {"execute_tool", "writer", "finalize"}:
            raise ValueError(f"unsupported critic route: {target}")
        return target  # type: ignore[return-value]

    def _finalize(self, state: ResearchState) -> ResearchState:
        started = time.monotonic()
        self._guard(state)
        self._checkpoint(state, "FINALIZE", {}, END, started)
        state["sink"].emit("FINALIZE", "COMPLETED", "报告进入人工审核", 100)
        return {}

    @staticmethod
    def _loop_progress(state: ResearchState, offset: int) -> int:
        maximum = max(1, state["command"].maxToolCalls)
        base = 20 + int(state.get("tool_calls", 0) / maximum * 58)
        return min(78, base + offset)

    def _command_with_remaining_tokens(self, state: ResearchState) -> TaskCommand:
        consumed = sum(item.outputTokens for item in state.get("usage", []))
        remaining = state["command"].maxTokens - consumed
        if remaining <= 0:
            raise ValueError("model output-token budget exhausted")
        return state["command"].model_copy(update={"maxTokens": remaining})

    def _append_usage(
        self, state: ResearchState, usage: ModelUsageResult
    ) -> list[ModelUsageResult]:
        items = [*state.get("usage", []), usage]
        if sum(item.outputTokens for item in items) > state["command"].maxTokens:
            raise ValueError("model output-token budget exceeded")
        return items

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
            "schemaVersion": 4,
            "commandHash": state["command_hash"],
            "completedNode": completed_node,
            "nextNode": next_node,
            "deadlineAt": state["deadline_at"],
            "question": state.get("question"),
            "plan": state["plan"].model_dump() if state.get("plan") else None,
            "searchHits": [item.model_dump() for item in state.get("search_hits", [])],
            "documents": [item.model_dump() for item in state.get("documents", [])],
            "attemptedQueries": state.get("attempted_queries", []),
            "attemptedInternalQueries": state.get("attempted_internal_queries", []),
            "attemptedUrls": state.get("attempted_urls", []),
            "action": state["action"].model_dump() if state.get("action") else None,
            "toolResult": state["tool_result"].model_dump() if state.get("tool_result") else None,
            "decisionCount": state.get("decision_count", 0),
            "researchComplete": state.get("research_complete", False),
            "sources": [item.model_dump() for item in state.get("sources", [])],
            "evidence": [item.model_dump() for item in state.get("evidence", [])],
            "claims": [item.model_dump() for item in state.get("claims", [])],
            "citations": [item.model_dump() for item in state.get("citations", [])],
            "criticReview": (
                state["critic_review"].model_dump() if state.get("critic_review") else None
            ),
            "title": state.get("title"),
            "content": state.get("content"),
            "qualityScore": state.get("quality_score"),
            "revisions": state.get("revisions", 0),
            "toolCalls": state.get("tool_calls", 0),
            "needsRevision": state.get("needs_revision", False),
            "criticNext": state.get("critic_next", "finalize"),
            "evidenceNext": state.get("evidence_next", "decide_action"),
            "supplementalCount": state.get("supplemental_count", 0),
            "supplementalPending": state.get("supplemental_pending", False),
            "providerName": state.get("provider_name", self.provider_name),
            "usage": [item.model_dump() for item in state.get("usage", [])],
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)

    def _restore_checkpoint(self, raw: str, command_hash: str) -> ResearchState:
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError("checkpoint is not valid JSON") from exc
        if payload.get("commandHash") != command_hash:
            raise ValueError("checkpoint does not belong to this task command")
        schema_version = payload.get("schemaVersion")
        if schema_version != 4:
            raise ValueError("only checkpoint schema version 4 is supported")
        target = payload.get("nextNode")
        if not isinstance(target, str):
            raise ValueError("checkpoint has no resume target")
        restored: ResearchState = {
            "resume_target": target,
            "deadline_at": float(payload["deadlineAt"]),
            "command_hash": command_hash,
            "search_hits": [SearchHit.model_validate(item) for item in payload.get("searchHits") or []],
            "documents": [
                FetchedDocument.model_validate(item) for item in payload.get("documents") or []
            ],
            "attempted_queries": list(payload.get("attemptedQueries") or []),
            "attempted_internal_queries": list(payload.get("attemptedInternalQueries") or []),
            "attempted_urls": list(payload.get("attemptedUrls") or []),
            "decision_count": int(payload.get("decisionCount") or 0),
            "research_complete": bool(payload.get("researchComplete")),
            "sources": [SourceResult.model_validate(item) for item in payload.get("sources") or []],
            "evidence": [
                EvidenceResult.model_validate(item) for item in payload.get("evidence") or []
            ],
            "claims": [ClaimDraft.model_validate(item) for item in payload.get("claims") or []],
            "citations": [
                CitationResult.model_validate(item) for item in payload.get("citations") or []
            ],
            "revisions": int(payload.get("revisions") or 0),
            "tool_calls": int(payload.get("toolCalls") or 0),
            "needs_revision": bool(payload.get("needsRevision")),
            "critic_next": payload.get("criticNext") or "finalize",
            "evidence_next": payload.get("evidenceNext") or "decide_action",
            "supplemental_count": int(payload.get("supplementalCount") or 0),
            "supplemental_pending": bool(payload.get("supplementalPending")),
            "provider_name": payload.get("providerName") or self.provider_name,
            "usage": [ModelUsageResult.model_validate(item) for item in payload.get("usage") or []],
        }
        typed_fields = {
            "plan": ("plan", ResearchPlan),
            "action": ("action", ToolAction),
            "toolResult": ("tool_result", ToolExecutionResult),
            "criticReview": ("critic_review", CriticReview),
        }
        for source_name, (state_name, model) in typed_fields.items():
            value = payload.get(source_name)
            if value is not None:
                restored[state_name] = model.model_validate(value)  # type: ignore[literal-required]
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

    @staticmethod
    def _command_hash(command: TaskCommand) -> str:
        canonical = json.dumps(
            command.model_dump(exclude={"eventId", "occurredAt", "traceId"}),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
