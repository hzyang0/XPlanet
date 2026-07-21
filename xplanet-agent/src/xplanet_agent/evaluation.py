from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path
from typing import Any

from .models import TaskCommand
from .workflow import ResearchWorkflow


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw.strip():
            continue
        try:
            cases.append(json.loads(raw))
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSONL at {path}:{line_number}") from exc
    if not cases:
        raise ValueError("evaluation dataset is empty")
    return cases


def evaluate_cases(cases: list[dict[str, Any]]) -> dict[str, Any]:
    results = []
    latencies = []
    total_input_tokens = 0
    total_output_tokens = 0
    total_estimated_cost = 0.0
    for index, case in enumerate(cases, start=1):
        started = time.perf_counter()
        try:
            command = TaskCommand(
                eventId=f"eval-event-{index}",
                eventType="AI_TASK_REQUESTED",
                taskId=index,
                runId=f"eval-run-{index}",
                userId=1,
                question=case["question"],
                maxSources=case.get("maxSources", 3),
                maxToolCalls=case.get("maxToolCalls", 3),
                maxTokens=case.get("maxTokens", 4000),
                deadlineSeconds=case.get("deadlineSeconds", 60),
            )
            report = ResearchWorkflow().run(command)
            case_input_tokens = sum(item.inputTokens for item in report.usage)
            case_output_tokens = sum(item.outputTokens for item in report.usage)
            case_estimated_cost = sum(item.estimatedCost for item in report.usage)
            total_input_tokens += case_input_tokens
            total_output_tokens += case_output_tokens
            total_estimated_cost += case_estimated_cost
            source_refs = {source.sourceRef for source in report.sources}
            evidence_refs = {item.evidenceRef for item in report.evidence}
            cited_refs = {item.evidenceRef for item in report.citations}
            claim_scores: dict[str, float] = {}
            for citation in report.citations:
                claim_scores[citation.claimId] = max(
                    claim_scores.get(citation.claimId, 0.0), citation.supportScore
                )
            claim_support_rate = sum(score >= 0.55 for score in claim_scores.values()) / max(
                1, len(claim_scores)
            )
            citation_index_valid = cited_refs.issubset(evidence_refs)
            evidence_source_valid = all(
                item.sourceRef in source_refs for item in report.evidence
            )
            citation_coverage = len(cited_refs & evidence_refs) / max(1, len(evidence_refs))
            bounded = (
                len(report.sources) <= command.maxSources
                and len(report.sources) >= case.get("minSources", 1)
            )
            expected_claim_support = case.get("minClaimSupportRate", 1.0)
            success = (
                citation_index_valid
                and evidence_source_valid
                and bounded
                and claim_support_rate >= expected_claim_support
            )
            result = {
                "id": case["id"],
                "success": success,
                "sourceCount": len(report.sources),
                "evidenceCount": len(report.evidence),
                "citationIndexValid": citation_index_valid,
                "evidenceSourceValid": evidence_source_valid,
                "citationCoverage": round(citation_coverage, 4),
                "claimCount": len(claim_scores),
                "claimSupportRate": round(claim_support_rate, 4),
                "structuralQualityScore": report.qualityScore,
                "inputTokens": case_input_tokens,
                "outputTokens": case_output_tokens,
                "estimatedCost": round(case_estimated_cost, 8),
            }
        except Exception as exc:
            result = {"id": case.get("id", str(index)), "success": False, "error": str(exc)}
        latency_ms = (time.perf_counter() - started) * 1000
        latencies.append(latency_ms)
        result["latencyMs"] = round(latency_ms, 3)
        results.append(result)

    ordered = sorted(latencies)
    p95_index = max(0, math.ceil(len(ordered) * 0.95) - 1)
    successful = [item for item in results if item["success"]]
    citation_valid = [item for item in results if item.get("citationIndexValid")]
    measured_support = [item["claimSupportRate"] for item in results if "claimSupportRate" in item]
    return {
        "provider": "offline-demo",
        "datasetSize": len(results),
        "successRate": round(len(successful) / len(results), 4),
        "citationIndexValidityRate": round(len(citation_valid) / len(results), 4),
        "claimSupportRate": round(sum(measured_support) / max(1, len(measured_support)), 4),
        "claimSupportThreshold": 0.55,
        "claimSupportMethod": "deterministic lexical guardrail; live semantic samples require human audit",
        "totalInputTokens": total_input_tokens,
        "totalOutputTokens": total_output_tokens,
        "estimatedCost": round(total_estimated_cost, 8),
        "usageNote": "offline-demo performs no external model calls; token and cost totals are therefore zero",
        "averageLatencyMs": round(sum(latencies) / len(latencies), 3),
        "p95LatencyMs": round(ordered[p95_index], 3),
        "cases": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic XPlanet Agent evaluation")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    summary = evaluate_cases(load_cases(args.dataset))
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if summary["successRate"] == 1 and summary["citationIndexValidityRate"] == 1 else 1


if __name__ == "__main__":
    raise SystemExit(main())
