#!/usr/bin/env python3
"""HTTP verification against one or two running instances. Creates isolated test users/equipment.

ADMIN_PASSWORD=<password> python scripts/booking_e2e.py --base-url http://localhost:8080 \
    --second-url http://localhost:8081 --users 32
"""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
import statistics
import time
import urllib.error
import urllib.request
import uuid


def request(base, path, method="GET", body=None, token=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + "/api" + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    # Local app traffic must not pass through a developer's HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=30) as response:
            status, data = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, data = error.code, json.load(error)
    assert status == expected, (path, status, data)
    return data


def verify(base, second=None, users=32):
    if not 2 <= users <= 100:
        raise ValueError("users must be 2..100")
    second = second or base
    password = os.environ["ADMIN_PASSWORD"]
    admin = request(base, "/auth/login", "POST", {"username": "admin", "password": password})["token"]
    prefix = "e2e_" + uuid.uuid4().hex[:8]
    resource = request(base, "/equipment", "POST", {"name": prefix, "category": "验证设备", "location": "测试区域", "description": "HTTP 并发验证自动创建", "approvalRequired": False, "trainingRequired": False}, admin)
    sessions = [request(base, "/auth/register", "POST", {"username": f"{prefix}_{i}", "password": "test-password-123"}) for i in range(users)]
    start = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).replace(hour=8, minute=0, second=0, microsecond=0, tzinfo=None)
    end = start + dt.timedelta(hours=1)
    payloads = [{"equipmentId": resource["id"], "startAt": start.isoformat(), "endAt": end.isoformat(), "purpose": "双实例并发验证", "requestKey": str(uuid.uuid4())} for _ in sessions]

    def submit(index):
        before = time.perf_counter()
        row = request(base if index % 2 == 0 else second, "/reservations", "POST", payloads[index], sessions[index]["token"])
        return index, row, (time.perf_counter() - before) * 1000

    began = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(submit, range(users)))
    elapsed = time.perf_counter() - began
    confirmed = [(i, r) for i, r, _ in results if r["status"] == "CONFIRMED"]
    assert len(confirmed) == 1
    assert sum(r["status"] == "WAITLISTED" for _, r, _ in results) == users - 1
    for i, row, _ in results:
        replay = request(second, "/reservations", "POST", payloads[i], sessions[i]["token"])
        assert replay["id"] == row["id"]
    winner_i, winner = confirmed[0]
    other_i = next(i for i in range(users) if i != winner_i)
    request(base, f'/reservations/{winner["id"]}/cancel', "POST", {}, sessions[other_i]["token"], expected=403)
    request(second, f'/reservations/{winner["id"]}/cancel', "POST", {}, sessions[winner_i]["token"])
    waiters = sorted([(i, r) for i, r, _ in results if r["status"] == "WAITLISTED"], key=lambda pair: pair[1]["id"])
    next_i, next_row = waiters[0]
    offered = request(base, f'/reservations/{next_row["id"]}', token=sessions[next_i]["token"])
    assert offered["status"] == "OFFERED"
    accepted = request(second, f'/reservations/{next_row["id"]}/accept', "POST", {}, sessions[next_i]["token"])
    assert accepted["status"] == "CONFIRMED"
    schedule = request(base, f'/equipment/{resource["id"]}/schedule?from={start.isoformat()}&to={end.isoformat()}', token=admin)
    assert len(schedule) == 2
    request(base, f'/equipment/{resource["id"]}/enabled', "PUT", {"enabled": False, "expectedVersion": resource["version"]}, admin)
    durations = sorted(ms for _, _, ms in results)
    return {"users": users, "instances": 2 if second != base else 1, "confirmed": 1, "waitlisted": users - 1,
            "idempotent_replays": users, "fifo_promotion": "passed", "ownership_check": "passed", "occupied_slots": len(schedule),
            "submission_batch_seconds": round(elapsed, 3), "latency_p50_ms": round(statistics.median(durations), 2),
            "latency_p95_ms": round(durations[min(len(durations)-1, int(len(durations)*0.95))], 2),
            "note": "Functional concurrency sample; not a production throughput benchmark."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--second-url")
    parser.add_argument("--users", type=int, default=32)
    args = parser.parse_args()
    print(json.dumps(verify(args.base_url, args.second_url, args.users), ensure_ascii=False, indent=2))
