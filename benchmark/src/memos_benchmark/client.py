"""Bounded standard-library client used by the benchmark harness."""

from __future__ import annotations

import json
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID

MAX_RESPONSE_BYTES = 1024 * 1024
MATERIALIZATION_STATES = {"PROCESSING", "SUCCEEDED", "FAILED"}
JOB_STATES = {"PENDING", "CLAIMED", "RETRY_WAIT", "SUCCEEDED", "DEAD"}
JOB_TYPES = {"MATERIALIZE_SOURCE", "CANDIDATE_MATERIALIZATION", "PROJECTION_BUILD"}
JOB_TYPE_ORDER = {
    "MATERIALIZE_SOURCE": 1,
    "CANDIDATE_MATERIALIZATION": 2,
    "PROJECTION_BUILD": 3,
}


class MemosClientError(RuntimeError):
    """Content-safe API or materialization failure."""

    def __init__(self, kind: str, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.kind = kind
        self.status = status


@dataclass(frozen=True)
class HealthStatus:
    status: str


@dataclass(frozen=True)
class MaterializationJobStatus:
    job_id: str
    source_event_id: str
    job_type: str
    state: str
    attempt: int
    max_attempts: int
    next_attempt_at: datetime | None
    lease_expires_at: datetime | None
    error_class: str | None
    replay_count: int
    completed_at: datetime | None
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class SourceMaterializationStatus:
    source_event_id: str
    status: str
    created_at: datetime
    updated_at: datetime
    settled_at: datetime | None
    jobs: tuple[MaterializationJobStatus, ...]


class MemosClient:
    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        *,
        monotonic: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        if not base_url.startswith(("http://", "https://")):
            raise ValueError("MemOS base_url must use http or https")
        self._base_url = base_url.rstrip("/")
        self._monotonic = monotonic
        self._sleeper = sleeper

    def readiness(self, timeout_seconds: float = 5.0) -> HealthStatus:
        payload = self._get_json("/readyz", None, timeout_seconds)
        status = payload.get("status")
        if not isinstance(status, str) or not status:
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS readiness response is invalid")
        return HealthStatus(status=status)

    def source_materialization(
        self,
        source_event_id: str,
        bearer_token: str,
        timeout_seconds: float = 5.0,
    ) -> SourceMaterializationStatus:
        canonical_source_id = _uuid(source_event_id, "source_event_id")
        if not bearer_token:
            raise ValueError("bearer_token must not be empty")
        payload = self._get_json(
            f"/v1/source-events/{canonical_source_id}/materialization",
            bearer_token,
            timeout_seconds,
        )
        return _source_materialization(payload, canonical_source_id)

    def wait_for_materialization(
        self,
        source_event_id: str,
        bearer_token: str,
        *,
        settle_timeout_seconds: float = 300.0,
        poll_interval_seconds: float = 0.25,
        request_timeout_seconds: float = 5.0,
    ) -> SourceMaterializationStatus:
        if settle_timeout_seconds <= 0:
            raise ValueError("settle_timeout_seconds must be positive")
        if poll_interval_seconds <= 0:
            raise ValueError("poll_interval_seconds must be positive")
        if request_timeout_seconds <= 0:
            raise ValueError("request_timeout_seconds must be positive")
        deadline = self._monotonic() + settle_timeout_seconds
        while True:
            remaining = deadline - self._monotonic()
            if remaining <= 0:
                raise MemosClientError(
                    "MATERIALIZATION_TIMEOUT", "MemOS materialization did not settle in time"
                )
            observed = self.source_materialization(
                source_event_id,
                bearer_token,
                min(request_timeout_seconds, remaining),
            )
            if observed.status == "SUCCEEDED":
                return observed
            if observed.status == "FAILED":
                raise MemosClientError(
                    "MATERIALIZATION_FAILED", "MemOS materialization reached a failed state"
                )
            remaining = deadline - self._monotonic()
            if remaining <= 0:
                raise MemosClientError(
                    "MATERIALIZATION_TIMEOUT", "MemOS materialization did not settle in time"
                )
            self._sleeper(min(poll_interval_seconds, remaining))

    def _get_json(
        self, path: str, bearer_token: str | None, timeout_seconds: float
    ) -> dict[str, Any]:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        headers = {"Accept": "application/json"}
        if bearer_token is not None:
            headers["Authorization"] = f"Bearer {bearer_token}"
        request = Request(f"{self._base_url}{path}", headers=headers, method="GET")
        try:
            with urlopen(request, timeout=timeout_seconds) as response:  # noqa: S310
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as exc:
            raise MemosClientError(
                "HTTP_ERROR", f"MemOS returned HTTP {exc.code}", exc.code
            ) from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise MemosClientError("TRANSPORT", "MemOS request failed") from exc
        if len(raw) > MAX_RESPONSE_BYTES:
            raise MemosClientError("RESPONSE_TOO_LARGE", "MemOS response exceeded the byte limit")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS response is not JSON") from exc
        if not isinstance(payload, dict):
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS response is not an object")
        return payload


def _source_materialization(
    payload: dict[str, Any], expected_source_id: str
) -> SourceMaterializationStatus:
    source_event_id = _uuid(payload.get("sourceEventId"), "sourceEventId")
    status = payload.get("status")
    raw_jobs = payload.get("jobs")
    if source_event_id != expected_source_id:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS source identity changed")
    if status not in MATERIALIZATION_STATES:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization status is invalid")
    if not isinstance(raw_jobs, list) or not raw_jobs:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization jobs are absent")
    jobs = tuple(_job(value, source_event_id) for value in raw_jobs)
    if len({job.job_id for job in jobs}) != len(jobs):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization jobs are duplicated")
    job_order = tuple(JOB_TYPE_ORDER[job.job_type] for job in jobs)
    if job_order != tuple(sorted(job_order)):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization jobs are unordered")
    settled_at = _timestamp(payload.get("settledAt"), "settledAt", optional=True)
    derived_status = (
        "FAILED"
        if any(job.state == "DEAD" for job in jobs)
        else "SUCCEEDED"
        if all(job.state == "SUCCEEDED" for job in jobs)
        else "PROCESSING"
    )
    if status != derived_status:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS aggregate state is inconsistent")
    created_at = _required_timestamp(payload.get("createdAt"), "createdAt")
    updated_at = _required_timestamp(payload.get("updatedAt"), "updatedAt")
    expected_settled_at = max(
        (job.completed_at for job in jobs if job.completed_at is not None), default=None
    )
    if created_at != min(job.created_at for job in jobs):
        raise MemosClientError(
            "MALFORMED_RESPONSE", "MemOS aggregate creation time is inconsistent"
        )
    if updated_at != max(job.updated_at for job in jobs):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS aggregate update time is inconsistent")
    if settled_at != (None if status == "PROCESSING" else expected_settled_at):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS settled timestamp is inconsistent")
    return SourceMaterializationStatus(
        source_event_id=source_event_id,
        status=status,
        created_at=created_at,
        updated_at=updated_at,
        settled_at=settled_at,
        jobs=jobs,
    )


def _job(value: Any, source_event_id: str) -> MaterializationJobStatus:
    if not isinstance(value, dict):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization job is invalid")
    job_source_id = _uuid(value.get("sourceEventId"), "job.sourceEventId")
    job_type = value.get("jobType")
    state = value.get("state")
    attempt = _non_negative_int(value.get("attempt"), "job.attempt")
    max_attempts = _positive_int(value.get("maxAttempts"), "job.maxAttempts")
    replay_count = _non_negative_int(value.get("replayCount"), "job.replayCount")
    error_class = value.get("errorClass")
    if job_source_id != source_event_id:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS job source identity changed")
    if job_type not in JOB_TYPES or state not in JOB_STATES or attempt > max_attempts:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization job state is invalid")
    if error_class is not None and (not isinstance(error_class, str) or not error_class):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS job error class is invalid")
    completed_at = _timestamp(value.get("completedAt"), "job.completedAt", optional=True)
    if (state in {"SUCCEEDED", "DEAD"}) != (completed_at is not None):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS job completion is inconsistent")
    next_attempt_at = _timestamp(value.get("nextAttemptAt"), "job.nextAttemptAt", optional=True)
    lease_expires_at = _timestamp(value.get("leaseExpiresAt"), "job.leaseExpiresAt", optional=True)
    if (state in {"PENDING", "RETRY_WAIT"}) != (next_attempt_at is not None):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS job schedule is inconsistent")
    if (state == "CLAIMED") != (lease_expires_at is not None):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS job lease is inconsistent")
    created_at = _required_timestamp(value.get("createdAt"), "job.createdAt")
    updated_at = _required_timestamp(value.get("updatedAt"), "job.updatedAt")
    if updated_at < created_at or (completed_at is not None and completed_at > updated_at):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS job timestamps are inconsistent")
    return MaterializationJobStatus(
        job_id=_uuid(value.get("jobId"), "job.jobId"),
        source_event_id=job_source_id,
        job_type=job_type,
        state=state,
        attempt=attempt,
        max_attempts=max_attempts,
        next_attempt_at=next_attempt_at,
        lease_expires_at=lease_expires_at,
        error_class=error_class,
        replay_count=replay_count,
        completed_at=completed_at,
        created_at=created_at,
        updated_at=updated_at,
    )


def _uuid(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} is invalid")
    try:
        return str(UUID(value))
    except ValueError as exc:
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} is invalid") from exc


def _timestamp(value: Any, field: str, *, optional: bool = False) -> datetime | None:
    if value is None and optional:
        return None
    if not isinstance(value, str):
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} is invalid")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} is invalid") from exc
    if parsed.tzinfo is None:
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} lacks a timezone")
    return parsed


def _required_timestamp(value: Any, field: str) -> datetime:
    parsed = _timestamp(value, field)
    if parsed is None:
        raise AssertionError("required timestamp parser returned None")
    return parsed


def _non_negative_int(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} is invalid")
    return value


def _positive_int(value: Any, field: str) -> int:
    parsed = _non_negative_int(value, field)
    if parsed == 0:
        raise MemosClientError("MALFORMED_RESPONSE", f"MemOS {field} is invalid")
    return parsed
