#!/usr/bin/env python3
"""Bounded HTTP cycle load in an isolated booking environment; no production QPS extrapolation."""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import time
import uuid
from booking_e2e import request


def verify(base, second, seconds, workers, resources):
    if not (1 <= seconds <= 300 and 1 <= workers <= 32 and 1 <= resources <= 32):
        raise ValueError("seconds: 1..300; workers/resources: 1..32")
    admin = request(base, "/auth/login", "POST", {"username": "admin", "password": os.environ["ADMIN_PASSWORD"]})["token"]
    prefix = "load_" + uuid.uuid4().hex[:8]
    equipment = [request(base, "/equipment", "POST", {"name": f"{prefix}_{i}", "category": "load verification",
        "location": "isolated", "description": "HTTP load fixture", "approvalRequired": False, "trainingRequired": False}, admin)
        for i in range(resources)]
    sessions = [request(base, "/auth/register", "POST", {"username": f"{prefix}_u{i}", "password": "test-password-123"}) for i in range(workers)]
    start = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=2)).replace(hour=8, minute=0, second=0, microsecond=0, tzinfo=None)
    stop_at = time.perf_counter() + seconds

    def run(index):
        token = sessions[index]["token"]
        url = base if index % 2 == 0 else second
        stats = {"latencies": [], "confirmed": 0, "waitlisted": 0, "cycles": 0, "replays": 0, "errors": []}
        while time.perf_counter() < stop_at:
            row = None
            try:
                payload = {"equipmentId": equipment[index % resources]["id"], "startAt": start.isoformat(),
                    "endAt": (start + dt.timedelta(hours=1)).isoformat(), "purpose": "bounded load",
                    "requestKey": str(uuid.uuid4())}
                before = time.perf_counter()
                row = request(url, "/reservations", "POST", payload, token)
                stats["latencies"].append((time.perf_counter() - before) * 1000)
                assert row["status"] in ("CONFIRMED", "WAITLISTED")
                stats[row["status"].lower()] += 1
                if stats["cycles"] % 10 == 0:
                    assert request(second, "/reservations", "POST", payload, token)["id"] == row["id"]
                    stats["replays"] += 1
                request(url, f'/reservations/{row["id"]}/cancel', "POST", {}, token)
                stats["cycles"] += 1
            except Exception as error:
                stats["errors"].append(type(error).__name__ + ": " + str(error)[:200])
                if row is not None:
                    try:
                        request(url, f'/reservations/{row["id"]}/cancel', "POST", {}, token)
                    except Exception:
                        pass
                break
        return stats

    began = time.perf_counter()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(run, range(workers)))
        elapsed = time.perf_counter() - began
        latency = sorted(ms for result in results for ms in result["latencies"])
        errors = [error for result in results for error in result["errors"]]
        for e in equipment:
            slots = request(base, f'/equipment/{e["id"]}/schedule?from={start.isoformat()}&to={(start+dt.timedelta(hours=1)).isoformat()}', token=admin)
            assert slots == [], ("slot leak", e["id"], slots)
        def percentile(fraction):
            return round(latency[min(len(latency) - 1, int((len(latency) - 1) * fraction))], 2) if latency else 0
        result = {"seconds": round(elapsed, 3), "workers": workers, "equipment": resources,
            "instances": 2 if base != second else 1, "submissions": len(latency),
            "completed_cycles": sum(r["cycles"] for r in results), "confirmed": sum(r["confirmed"] for r in results),
            "waitlisted": sum(r["waitlisted"] for r in results), "idempotent_replays": sum(r["replays"] for r in results),
            "submission_requests_per_second": round(len(latency) / elapsed, 2), "submit_p50_ms": percentile(.5),
            "submit_p95_ms": percentile(.95), "submit_p99_ms": percentile(.99), "errors": errors,
            "slots_released": True, "fixture_prefix": prefix,
            "scope": "local bounded create/replay/cancel HTTP cycles; not maximum throughput or long-running soak"}
        if errors:
            raise RuntimeError(json.dumps(result, ensure_ascii=False))
        return result
    finally:
        for e in equipment:
            request(base, f'/equipment/{e["id"]}/enabled', "PUT", {"enabled": False, "expectedVersion": e["version"]}, admin)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--second-url")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--resources", type=int, default=1)
    parser.add_argument("--output")
    args = parser.parse_args()
    result = verify(args.base_url, args.second_url or args.base_url, args.seconds, args.workers, args.resources)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
