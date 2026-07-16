from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

import httpx

from .models import (
    CitationResult,
    EvidenceResult,
    ModelUsageResult,
    SourceResult,
    TaskCommand,
)


@dataclass(frozen=True)
class ProviderResearch:
    title: str
    content: str
    sources: list[SourceResult]
    evidence: list[EvidenceResult]
    citations: list[CitationResult]
    usage: list[ModelUsageResult]
    tool_calls: int


class ResearchProvider(Protocol):
    name: str

    def research(self, command: TaskCommand) -> ProviderResearch: ...


class OpenAIWebResearchProvider:
    """Optional Responses API + hosted web search adapter.

    It treats URL annotations as citation-index evidence only. A later fetch/verifier
    stage is still required before claiming semantic or factual support.
    """

    name = "openai-web"

    def __init__(
        self,
        api_key: str,
        model: str = "gpt-5.6-terra",
        base_url: str = "https://api.openai.com/v1",
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not api_key.strip():
            raise ValueError("OPENAI_API_KEY is required for AGENT_PROVIDER=openai-web")
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._transport = transport

    def research(self, command: TaskCommand) -> ProviderResearch:
        prompt = (
            "Research the following technical question using web search. Produce a concise Markdown report with "
            "explicit trade-offs, implementation guidance, and inline citations. Use only sources returned by the "
            f"web search tool, use at most {command.maxToolCalls} search calls, surface uncertainty, and do not "
            "invent URLs.\n\n"
            f"Question: {command.question}"
        )
        payload = {
            "model": self._model,
            "tools": [{"type": "web_search", "search_context_size": "medium"}],
            "tool_choice": "required",
            "include": ["web_search_call.action.sources"],
            "max_output_tokens": min(command.maxTokens, 12_000),
            "input": prompt,
        }
        started = time.monotonic()
        timeout = min(float(command.deadlineSeconds), 180.0)
        with httpx.Client(transport=self._transport, timeout=timeout) as client:
            response = client.post(
                f"{self._base_url}/responses",
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                    "X-Client-Request-Id": command.runId,
                },
                json=payload,
            )
            response.raise_for_status()
            body = response.json()
        latency_ms = max(0, int((time.monotonic() - started) * 1000))
        tool_calls = sum(
            1 for item in body.get("output") or [] if item.get("type") == "web_search_call"
        )
        if tool_calls < 1:
            raise ValueError("OpenAI web response did not contain a web search call")
        if tool_calls > command.maxToolCalls:
            raise ValueError("OpenAI web response exceeded the tool-call budget")
        text, annotations = self._message_text_and_annotations(body)
        cited = self._cited_sources(annotations, command.maxSources)
        if not text.strip() or not cited:
            raise ValueError("OpenAI web response did not contain cited output text")

        retrieved_at = datetime.now(timezone.utc).isoformat()
        sources: list[SourceResult] = []
        evidence: list[EvidenceResult] = []
        citations: list[CitationResult] = []
        source_links = []
        for index, item in enumerate(cited, start=1):
            source_ref = f"src-{index}"
            evidence_ref = f"ev-{index}"
            context = self._citation_context(text, item)
            metadata = json.dumps(
                {
                    "provider": self.name,
                    "model": self._model,
                    "evidenceType": "response-citation-context",
                    "semanticSupportVerified": False,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            )
            sources.append(
                SourceResult(
                    sourceRef=source_ref,
                    url=item["url"],
                    title=item.get("title") or item["url"],
                    contentHash=hashlib.sha256(context.encode("utf-8")).hexdigest(),
                    retrievedAt=retrieved_at,
                    metadataJson=metadata,
                )
            )
            evidence.append(
                EvidenceResult(
                    evidenceRef=evidence_ref,
                    sourceRef=source_ref,
                    locator="Responses API url_citation context",
                    content=context,
                    score=0.5,
                )
            )
            citations.append(
                CitationResult(
                    claimId=f"claim-{index}",
                    evidenceRef=evidence_ref,
                    supportScore=0.5,
                )
            )
            source_links.append(f"- [{item.get('title') or item['url']}]({item['url']})")

        usage = body.get("usage") or {}
        content = text.rstrip() + "\n\n## 可点击来源\n\n" + "\n".join(source_links)
        return ProviderResearch(
            title=f"联网研究报告：{command.question[:80]}",
            content=content,
            sources=sources,
            evidence=evidence,
            citations=citations,
            usage=[
                ModelUsageResult(
                    nodeName="PARALLEL_RESEARCH",
                    provider="openai",
                    model=self._model,
                    inputTokens=int(usage.get("input_tokens") or 0),
                    outputTokens=int(usage.get("output_tokens") or 0),
                    estimatedCost=0,
                    latencyMs=latency_ms,
                    retryCount=0,
                )
            ],
            tool_calls=tool_calls,
        )

    def _message_text_and_annotations(self, body: dict) -> tuple[str, list[dict]]:
        texts = []
        annotations = []
        for item in body.get("output") or []:
            if item.get("type") != "message":
                continue
            for content in item.get("content") or []:
                if content.get("type") != "output_text":
                    continue
                texts.append(content.get("text") or "")
                annotations.extend(content.get("annotations") or [])
        return "\n".join(texts), annotations

    def _cited_sources(self, annotations: list[dict], limit: int) -> list[dict]:
        unique = []
        seen = set()
        for annotation in annotations:
            if annotation.get("type") != "url_citation":
                continue
            value = annotation.get("url_citation") or annotation
            url = value.get("url")
            if not isinstance(url, str) or not url.startswith(("https://", "http://")) or url in seen:
                continue
            seen.add(url)
            unique.append(value)
            if len(unique) >= limit:
                break
        return unique

    def _citation_context(self, text: str, annotation: dict) -> str:
        start = int(annotation.get("start_index") or 0)
        end = int(annotation.get("end_index") or start)
        left = max(0, start - 500)
        right = min(len(text), max(end, start) + 100)
        context = text[left:right].strip()
        return context or text[:600].strip()
