from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, model_validator


class TaskCommand(BaseModel):
    eventId: str
    eventType: str
    schemaVersion: int = 1
    taskId: int
    runId: str
    aggregateVersion: int = 0
    occurredAt: int | None = None
    traceId: str | None = None
    userId: int
    question: str
    provider: Literal["offline-demo", "openai-tools"] = "offline-demo"
    maxSources: int = Field(default=5, ge=1, le=20)
    maxToolCalls: int = Field(default=10, ge=1, le=50)
    maxTokens: int = Field(default=8000, ge=1000, le=100_000)
    deadlineSeconds: int = Field(default=300, ge=30, le=3600)


class PlanStep(BaseModel):
    stepId: str = Field(min_length=1, max_length=64)
    objective: str = Field(min_length=1, max_length=500)
    searchQuery: str = Field(min_length=1, max_length=300)


class ResearchPlan(BaseModel):
    steps: list[PlanStep] = Field(min_length=1, max_length=8)


class ToolAction(BaseModel):
    name: Literal["web_search", "web_fetch", "internal_search", "finish_research"]
    query: str | None = Field(default=None, max_length=300)
    url: str | None = Field(default=None, max_length=2048)
    reason: str = Field(min_length=1, max_length=500)

    @model_validator(mode="after")
    def validate_arguments(self) -> "ToolAction":
        if self.name == "web_search" and not (self.query or "").strip():
            raise ValueError("web_search requires query")
        if self.name == "web_fetch" and not (self.url or "").strip():
            raise ValueError("web_fetch requires url")
        if self.name == "internal_search" and not (self.query or "").strip():
            raise ValueError("internal_search requires query")
        if self.name == "finish_research" and (self.query or self.url):
            raise ValueError("finish_research does not accept query or url")
        return self


class SearchHit(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    title: str = Field(min_length=1, max_length=500)
    snippet: str = Field(min_length=1, max_length=4000)
    sourceType: Literal["web", "internal"] = "web"


class FetchedDocument(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    title: str = Field(min_length=1, max_length=500)
    content: str = Field(min_length=1, max_length=50_000)
    contentType: str = Field(default="text/plain", max_length=100)


class ToolExecutionResult(BaseModel):
    action: ToolAction
    searchHits: list[SearchHit] = Field(default_factory=list, max_length=20)
    document: FetchedDocument | None = None
    usage: list["ModelUsageResult"] = Field(default_factory=list)


class ClaimDraft(BaseModel):
    claimId: str = Field(pattern=r"^claim-[a-zA-Z0-9_-]+$", max_length=64)
    statement: str = Field(min_length=1, max_length=4000)
    evidenceRefs: list[str] = Field(min_length=1, max_length=5)
    confidence: float = Field(ge=0, le=1)


class WriterDraft(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=100_000)
    claims: list[ClaimDraft] = Field(min_length=1, max_length=50)


class CriticIssue(BaseModel):
    issueType: Literal[
        "missing_evidence",
        "conflicting_evidence",
        "incorrect_citation",
        "unsupported_claim",
    ]
    claimId: str | None = Field(default=None, max_length=64)
    evidenceRefs: list[str] = Field(default_factory=list, max_length=5)
    detail: str = Field(min_length=1, max_length=1000)
    suggestedQuery: str | None = Field(default=None, max_length=300)


class CriticReview(BaseModel):
    approved: bool
    qualityScore: float = Field(ge=0, le=1)
    claimSupportScore: float = Field(ge=0, le=1)
    issues: list[CriticIssue] = Field(default_factory=list, max_length=20)
    uncertainties: list[str] = Field(default_factory=list, max_length=20)
    conflicts: list[str] = Field(default_factory=list, max_length=20)
    supplementalQuery: str | None = Field(default=None, max_length=300)


class SourceResult(BaseModel):
    sourceRef: str
    url: str
    title: str
    contentHash: str
    retrievedAt: str
    metadataJson: str = "{}"


class EvidenceResult(BaseModel):
    evidenceRef: str
    sourceRef: str
    locator: str
    content: str
    contentHash: str = Field(pattern=r"^[0-9a-f]{64}$")
    score: float = Field(ge=0, le=1)


class CitationResult(BaseModel):
    claimId: str
    evidenceRef: str
    supportScore: float = Field(ge=0, le=1)


class ModelUsageResult(BaseModel):
    nodeName: str
    provider: str
    model: str
    inputTokens: int = Field(default=0, ge=0)
    outputTokens: int = Field(default=0, ge=0)
    estimatedCost: float = Field(default=0, ge=0)
    latencyMs: int = Field(default=0, ge=0)
    retryCount: int = Field(default=0, ge=0)


class ResearchResult(BaseModel):
    taskId: int
    runId: str
    title: str
    content: str
    qualityScore: float = Field(ge=0, le=1)
    provider: str
    sources: list[SourceResult]
    evidence: list[EvidenceResult]
    citations: list[CitationResult]
    usage: list[ModelUsageResult] = Field(default_factory=list)
