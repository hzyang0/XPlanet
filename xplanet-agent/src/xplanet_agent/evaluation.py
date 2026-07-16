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
            source_refs = {source.sourceRef for source in report.sources}
            evidence_refs = {item.evidenceRef for item in report.evidence}
            cited_refs = {item.evidenceRef for item in report.citations}
            citation_index_valid = cited_refs.issubset(evidence_refs)
            evidence_source_valid = all(
                item.sourceRef in source_refs for item in report.evidence
            )
            citation_coverage = len(cited_refs & evidence_refs) / max(1, len(evidence_refs))
            bounded = (
                len(report.sources) <= command.maxSources
                and len(report.sources) <= command.maxToolCalls
                and len(report.sources) >= case.get("minSources", 1)
            )
            success = citation_index_valid and evidence_source_valid and bounded
            result = {
                "id": case["id"],
                "success": success,
                "sourceCount": len(report.sources),
                "evidenceCount": len(report.evidence),
                "citationIndexValid": citation_index_valid,
                "evidenceSourceValid": evidence_source_valid,
                "citationCoverage": round(citation_coverage, 4),
                "structuralQualityScore": report.qualityScore,
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
    return {
        "provider": "offline-demo",
        "datasetSize": len(results),
        "successRate": round(len(successful) / len(results), 4),
        "citationIndexValidityRate": round(len(citation_valid) / len(results), 4),
        "claimSupportRate": None,
        "claimSupportRateNote": "not measured; index validity is not factual support",
        "averageLatencyMs": round(sum(latencies) / len(latencies), 3),
        "p95LatencyMs": round(ordered[p95_index], 3),
        "cases": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic XPlanet Agent evaluation")
    parser.add_argument("--dataset", type=Path, required=True)
    args = parser.parse_args()
    summary = evaluate_cases(load_cases(args.dataset))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["successRate"] == 1 and summary["citationIndexValidityRate"] == 1 else 1


if __name__ == "__main__":
    raise SystemExit(main())
