from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Protocol

import httpx

logger = logging.getLogger(__name__)


class ProgressSink(Protocol):
    def emit(self, node: str, status: str, message: str, progress: int) -> None: ...

    def is_cancelled(self) -> bool: ...

    def load_checkpoint(self) -> str | None: ...

    def save_checkpoint(
        self,
        node: str,
        input_hash: str,
        state_json: str,
        duration_ms: int,
    ) -> None: ...


@dataclass
class NullProgressSink:
    def emit(self, node: str, status: str, message: str, progress: int) -> None:
        return None

    def is_cancelled(self) -> bool:
        return False

    def load_checkpoint(self) -> str | None:
        return None

    def save_checkpoint(
        self,
        node: str,
        input_hash: str,
        state_json: str,
        duration_ms: int,
    ) -> None:
        return None


class HttpProgressSink:
    def __init__(self, base_url: str, internal_token: str, task_id: int, run_id: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._headers = {"X-Agent-Token": internal_token}
        self._task_id = task_id
        self._run_id = run_id

    def emit(self, node: str, status: str, message: str, progress: int) -> None:
        payload = {
            "runId": self._run_id,
            "node": node,
            "status": status,
            "message": message[:500],
            "progress": max(0, min(progress, 100)),
        }
        try:
            with httpx.Client(timeout=3.0) as client:
                client.post(
                    f"{self._base_url}/internal/ai/tasks/{self._task_id}/progress",
                    headers=self._headers,
                    json=payload,
                ).raise_for_status()
        except Exception as exc:  # Progress is an acceleration path, not the task fact source.
            logger.warning("progress callback failed: %s", exc)

    def is_cancelled(self) -> bool:
        try:
            with httpx.Client(timeout=2.0) as client:
                response = client.get(
                    f"{self._base_url}/internal/ai/tasks/{self._task_id}/cancelled",
                    headers=self._headers,
                )
                response.raise_for_status()
                return bool(response.json().get("cancelled"))
        except Exception as exc:
            logger.warning("cancellation check failed: %s", exc)
            return False

    def load_checkpoint(self) -> str | None:
        with httpx.Client(timeout=3.0) as client:
            response = client.get(
                f"{self._base_url}/internal/ai/tasks/{self._task_id}/checkpoint",
                headers=self._headers,
                params={"runId": self._run_id},
            )
            response.raise_for_status()
            if not response.content:
                return None
            payload = response.json()
            return payload.get("stateJson") if payload else None

    def save_checkpoint(
        self,
        node: str,
        input_hash: str,
        state_json: str,
        duration_ms: int,
    ) -> None:
        payload = {
            "runId": self._run_id,
            "node": node,
            "inputHash": input_hash,
            "stateVersion": 4,
            "stateJson": state_json,
            "durationMs": max(0, duration_ms),
        }
        with httpx.Client(timeout=5.0) as client:
            client.post(
                f"{self._base_url}/internal/ai/tasks/{self._task_id}/checkpoint",
                headers=self._headers,
                json=payload,
            ).raise_for_status()
