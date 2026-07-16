from __future__ import annotations

from pydantic import BaseModel, Field


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
    maxSources: int = Field(default=5, ge=1, le=20)
    maxToolCalls: int = Field(default=10, ge=1, le=50)
    maxTokens: int = Field(default=8000, ge=1000, le=100_000)
    deadlineSeconds: int = Field(default=300, ge=30, le=3600)


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
    score: float = Field(ge=0, le=1)


class CitationResult(BaseModel):
    claimId: str
    evidenceRef: str
    supportScore: float = Field(ge=0, le=1)


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
