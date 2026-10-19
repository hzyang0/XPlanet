#!/usr/bin/env python3
"""Kill/restart verification using a real one-minute approval deadline.

Requires Java 17, built jar and ADMIN_PASSWORD. Uses a temporary H2 file unless
DB_URL/DB_USER/DB_PASSWORD are provided. Do not share the database with other workers.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid
from booking_e2e import request


def verify(jar, port):
    jar = str(Path(jar).resolve())
    if not Path(jar).is_file():
        raise ValueError("Build xplanet-booking first")
    if not os.environ.get("ADMIN_PASSWORD"):
        raise ValueError("Set ADMIN_PASSWORD before running")
    base = f"http://127.0.0.1:{port}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    process = None
    with tempfile.TemporaryDirectory(prefix="booking-recovery-") as work:
        env = {**os.environ, "SPRING_PROFILES_ACTIVE": ""}
        args = ["java", "-jar", jar, f"--server.port={port}", "--booking.scheduler-delay-ms=60000", "--booking.approval-minutes=1"]
        log = open(Path(work) / "app.log", "w+")

        def start():
            p = subprocess.Popen(args, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
            for _ in range(120):
                try:
                    with opener.open(base + "/actuator/health", timeout=1) as response:
                        if response.status == 200:
                            return p
                except Exception:
                    pass
                if p.poll() is not None:
                    break
                time.sleep(0.25)
            p.terminate()
            p.wait(timeout=10)
            log.flush()
            raise RuntimeError(Path(work, "app.log").read_text()[-3000:])

        try:
            process = start()
            for attempt in range(5):
                try:
                    admin = request(base, "/auth/login", "POST", {"username": "admin", "password": env["ADMIN_PASSWORD"]})["token"]
                    break
                except AssertionError:
                    if attempt == 4:
                        raise
                    time.sleep(0.5)
            prefix = "recover_" + uuid.uuid4().hex[:6]
            users = [request(base, "/auth/register", "POST", {"username": prefix + str(i), "password": "test-password-123"}) for i in range(2)]
            eq = request(base, "/equipment", "POST", {"name": prefix, "category": "恢复验证", "location": "测试区域", "description": "进程退出与真实期限验证", "approvalRequired": True, "trainingRequired": False}, admin)
            start_at = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).replace(hour=8, minute=0, second=0, microsecond=0, tzinfo=None)
            payload = {"equipmentId": eq["id"], "startAt": start_at.isoformat(), "endAt": (start_at + dt.timedelta(hours=1)).isoformat(), "purpose": "重启恢复", "requestKey": str(uuid.uuid4())}
            first = request(base, "/reservations", "POST", payload, users[0]["token"])
            second = request(base, "/reservations", "POST", {**payload, "requestKey": str(uuid.uuid4())}, users[1]["token"])
            process.kill()
            process.wait(timeout=10)
            deadline = dt.datetime.fromisoformat(first["deadline"]).replace(tzinfo=dt.timezone.utc)
            print("Application killed. Waiting for the real persisted deadline...", flush=True)
            while dt.datetime.now(dt.timezone.utc) < deadline + dt.timedelta(seconds=1):
                time.sleep(1)
            process = start()
            for _ in range(100):
                a = request(base, f'/reservations/{first["id"]}', token=users[0]["token"])
                b = request(base, f'/reservations/{second["id"]}', token=users[1]["token"])
                notes = request(base, "/notifications", token=users[1]["token"])
                if a["status"] == "EXPIRED" and b["status"] == "OFFERED" and any("OFFERED" in n["message"] for n in notes):
                    break
                time.sleep(0.2)
            assert a["status"] == "EXPIRED" and b["status"] == "OFFERED"
            assert any("OFFERED" in n["message"] for n in notes)
            assert request(base, "/reservations", "POST", payload, users[0]["token"])["id"] == first["id"]
            return {"hard_kill": "passed", "real_deadline": "passed", "session_persistence": "passed", "approval_expiry": a["status"], "waiter": b["status"], "notification_recovery": "passed", "idempotent_replay": "passed"}
        finally:
            if process and process.poll() is None:
                process.terminate()
                process.wait(timeout=15)
            log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", default="xplanet-booking/target/xplanet-booking-2.0.0.jar")
    parser.add_argument("--port", type=int, default=18090)
    args = parser.parse_args()
    print(json.dumps(verify(args.jar, args.port), indent=2, ensure_ascii=False))
