from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .models import TaskCommand, ToolAction
from .tools import HttpInternalSearchProvider


def evaluate(dataset: Path, provider: HttpInternalSearchProvider) -> dict:
    cases = [json.loads(line) for line in dataset.read_text(encoding="utf-8").splitlines() if line]
    results = []
    for index, case in enumerate(cases, start=1):
        command = TaskCommand(
            eventId=f"recall-{index}",
            eventType="AI_TASK_REQUESTED",
            taskId=index,
            runId=f"recall-run-{index}",
            userId=1,
            question=case["query"],
            maxSources=5,
            maxToolCalls=1,
            maxTokens=1000,
            deadlineSeconds=30,
        )
        action = ToolAction(
            name="internal_search",
            query=case["query"],
            reason="recall evaluation",
        )
        hits = provider.search(command, action, 5).searchHits
        returned_ids = [int(hit.url.rsplit("/", 1)[-1]) for hit in hits]
        expected = set(case["expectedArticleIds"])
        recalled = bool(expected.intersection(returned_ids))
        results.append(
            {
                "id": case["id"],
                "recalled": recalled,
                "expectedArticleIds": sorted(expected),
                "returnedArticleIds": returned_ids,
            }
        )
    recall_at_5 = sum(item["recalled"] for item in results) / max(1, len(results))
    return {
        "datasetSize": len(results),
        "recallAt5": round(recall_at_5, 4),
        "cases": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate XPlanet MySQL internal-search recall")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument(
        "--base-url",
        default=os.getenv("ARTICLE_SERVICE_URL", "http://article:8081"),
    )
    args = parser.parse_args()
    provider = HttpInternalSearchProvider(
        base_url=args.base_url,
        internal_token=os.getenv("AGENT_INTERNAL_TOKEN", ""),
    )
    summary = evaluate(args.dataset, provider)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["recallAt5"] >= 0.8 else 1


if __name__ == "__main__":
    raise SystemExit(main())
