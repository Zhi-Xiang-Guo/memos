"""Bounded standard-library client used by the benchmark harness."""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID

STORAGE_RELATIONS = (
    "memos.audit_event",
    "memos.candidate_policy_decision",
    "memos.deletion_request",
    "memos.erasure_tombstone",
    "memos.extraction_attempt",
    "memos.extraction_run",
    "memos.materialization_result",
    "memos.memory_candidate",
    "memos.memory_current_state",
    "memos.memory_lineage",
    "memos.memory_mutation_request",
    "memos.memory_projection_checkpoint",
    "memos.memory_quarantine",
    "memos.memory_search_projection",
    "memos.memory_source",
    "memos.memory_state_transition",
    "memos.memory_status_change",
    "memos.memory_version",
    "memos.outbox_job",
    "memos.projection_provider_usage",
    "memos.source_event",
)

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
    usage: MaterializationUsage
    jobs: tuple[MaterializationJobStatus, ...]


@dataclass(frozen=True)
class MaterializationUsage:
    complete: bool
    input_tokens: int
    output_tokens: int
    embedding_tokens: int
    model_calls: int


@dataclass(frozen=True)
class SourceEventReceipt:
    source_event_id: str
    source_id: str
    materialization_job_id: str
    disposition: str
    accepted_at: datetime
    materialization_state: str


@dataclass(frozen=True)
class RetrievalContext:
    rendered: str
    tokens: int
    token_counter_version: str
    token_count_provider_input_tokens: int
    token_count_provider_calls: int
    selected_version_ids: tuple[str, ...]


@dataclass(frozen=True)
class RetrievalTrace:
    embedding_provider: str
    embedding_model_version: str
    embedding_input_tokens: int


@dataclass(frozen=True)
class RetrievalResponse:
    gate_retrieve: bool
    ranked_source_event_ids: tuple[str, ...]
    selected_source_event_ids: tuple[str, ...]
    context: RetrievalContext
    trace: RetrievalTrace


@dataclass(frozen=True)
class StorageRelationObservation:
    relation: str
    row_count: int
    row_bytes: int


@dataclass(frozen=True)
class StorageObservation:
    scope_row_count: int
    scope_row_bytes: int
    relations: tuple[StorageRelationObservation, ...]
    database_table_bytes: int
    database_index_bytes: int
    database_total_bytes: int


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

    def storage_observation(
        self, bearer_token: str, timeout_seconds: float = 10.0
    ) -> StorageObservation:
        if not bearer_token:
            raise ValueError("bearer_token must not be empty")
        payload = self._get_json("/v1/operations/storage", bearer_token, timeout_seconds)
        return _storage_observation(payload)

    def ingest_source_event(
        self,
        event: dict[str, Any],
        bearer_token: str,
        idempotency_key: str,
        timeout_seconds: float = 10.0,
    ) -> SourceEventReceipt:
        if not bearer_token or not idempotency_key:
            raise ValueError("bearer_token and idempotency_key must not be empty")
        if not isinstance(event, dict):
            raise ValueError("event must be an object")
        payload = self._request_json(
            "POST",
            "/v1/source-events",
            bearer_token,
            timeout_seconds,
            event,
            {"Idempotency-Key": idempotency_key},
            202,
        )
        return _source_receipt(payload)

    def retrieval_trace(
        self,
        query: str,
        bearer_token: str,
        *,
        limit: int,
        max_tokens: int,
        timeout_seconds: float = 300.0,
    ) -> RetrievalResponse:
        if not query or not bearer_token:
            raise ValueError("query and bearer_token must not be empty")
        if limit < 1 or max_tokens < 1:
            raise ValueError("limit and max_tokens must be positive")
        payload = self._request_json(
            "POST",
            "/v1/retrieval/trace",
            bearer_token,
            timeout_seconds,
            {
                "query": query,
                "mode": "HYBRID",
                "limit": limit,
                "componentLimit": max(40, limit),
                "rerank": False,
                "maxTokens": max_tokens,
            },
            {},
            200,
        )
        return _retrieval_response(payload)

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
        return self._request_json("GET", path, bearer_token, timeout_seconds, None, {}, 200)

    def _request_json(
        self,
        method: str,
        path: str,
        bearer_token: str | None,
        timeout_seconds: float,
        body: dict[str, Any] | None,
        extra_headers: dict[str, str],
        expected_status: int,
    ) -> dict[str, Any]:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        headers = {"Accept": "application/json"}
        encoded = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            encoded = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if bearer_token is not None:
            headers["Authorization"] = f"Bearer {bearer_token}"
        headers.update(extra_headers)
        request = Request(f"{self._base_url}{path}", data=encoded, headers=headers, method=method)
        try:
            with urlopen(request, timeout=timeout_seconds) as response:  # noqa: S310
                if getattr(response, "status", expected_status) != expected_status:
                    raise MemosClientError("HTTP_ERROR", "MemOS returned unexpected HTTP status")
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


def _storage_observation(payload: dict[str, Any]) -> StorageObservation:
    if set(payload) != {"schemaVersion", "scope", "database"}:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS storage response is invalid")
    if payload["schemaVersion"] != "memos-storage-observation.v1":
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS storage schema is unsupported")
    scope = payload["scope"]
    database = payload["database"]
    if not isinstance(scope, dict) or set(scope) != {"rowCount", "rowBytes", "relations"}:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS scope storage is invalid")
    if not isinstance(database, dict) or set(database) != {
        "tableBytes",
        "indexBytes",
        "totalBytes",
    }:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS database storage is invalid")
    raw_relations = scope["relations"]
    if not isinstance(raw_relations, list) or not raw_relations or len(raw_relations) > 64:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS storage relations are invalid")
    relations: list[StorageRelationObservation] = []
    names: set[str] = set()
    for value in raw_relations:
        if not isinstance(value, dict) or set(value) != {"relation", "rowCount", "rowBytes"}:
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS storage relation is invalid")
        relation = value["relation"]
        if (
            not isinstance(relation, str)
            or re.fullmatch(r"memos\.[a-z][a-z0-9_]{0,126}", relation) is None
            or relation in names
        ):
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS storage relation is invalid")
        names.add(relation)
        row_count = _non_negative_int(value["rowCount"], "storage.relation.rowCount")
        row_bytes = _non_negative_int(value["rowBytes"], "storage.relation.rowBytes")
        if row_count == 0 and row_bytes != 0:
            raise MemosClientError("MALFORMED_RESPONSE", "empty storage relation has bytes")
        relations.append(StorageRelationObservation(relation, row_count, row_bytes))
    if tuple(value.relation for value in relations) != STORAGE_RELATIONS:
        raise MemosClientError(
            "MALFORMED_RESPONSE", "MemOS storage relation contract is incomplete or unordered"
        )
    scope_row_count = _non_negative_int(scope["rowCount"], "storage.scope.rowCount")
    scope_row_bytes = _non_negative_int(scope["rowBytes"], "storage.scope.rowBytes")
    if scope_row_count != sum(value.row_count for value in relations) or scope_row_bytes != sum(
        value.row_bytes for value in relations
    ):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS scope storage totals are invalid")
    table_bytes = _non_negative_int(database["tableBytes"], "storage.database.tableBytes")
    index_bytes = _non_negative_int(database["indexBytes"], "storage.database.indexBytes")
    total_bytes = _non_negative_int(database["totalBytes"], "storage.database.totalBytes")
    if total_bytes != table_bytes + index_bytes:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS database storage totals are invalid")
    return StorageObservation(
        scope_row_count,
        scope_row_bytes,
        tuple(relations),
        table_bytes,
        index_bytes,
        total_bytes,
    )


def _source_receipt(payload: dict[str, Any]) -> SourceEventReceipt:
    fields = {
        "sourceEventId",
        "sourceId",
        "materializationJobId",
        "disposition",
        "acceptedAt",
        "materializationState",
    }
    if set(payload) != fields:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS source receipt schema is invalid")
    source_id = payload["sourceId"]
    disposition = payload["disposition"]
    state = payload["materializationState"]
    if (
        not isinstance(source_id, str)
        or not source_id
        or not isinstance(disposition, str)
        or not disposition
        or not isinstance(state, str)
        or not state
    ):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS source receipt values are invalid")
    return SourceEventReceipt(
        source_event_id=_uuid(payload["sourceEventId"], "sourceEventId"),
        source_id=source_id,
        materialization_job_id=_uuid(payload["materializationJobId"], "materializationJobId"),
        disposition=disposition,
        accepted_at=_required_timestamp(payload["acceptedAt"], "acceptedAt"),
        materialization_state=state,
    )


def _retrieval_response(payload: dict[str, Any]) -> RetrievalResponse:
    if set(payload) != {"gate", "intent", "context", "memories", "trace"}:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval response schema is invalid")
    gate = payload["gate"]
    if (
        not isinstance(gate, dict)
        or set(gate) != {"retrieve", "reason"}
        or not isinstance(gate["retrieve"], bool)
        or not isinstance(gate["reason"], str)
    ):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval gate is invalid")
    context = _retrieval_context(payload["context"])
    trace = _retrieval_trace(payload["trace"])
    memories = payload["memories"]
    if not isinstance(memories, list):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval memories are invalid")
    by_version: dict[str, tuple[str, ...]] = {}
    ranked: list[str] = []
    for memory in memories:
        if not isinstance(memory, dict):
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval memory is invalid")
        version_id = _uuid(memory.get("versionId"), "memory.versionId")
        if version_id in by_version:
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval versions are duplicated")
        raw_source_ids = memory.get("sourceEventIds")
        if not isinstance(raw_source_ids, list):
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS memory provenance is invalid")
        source_ids = tuple(_uuid(value, "memory.sourceEventId") for value in raw_source_ids)
        if not source_ids or len(source_ids) != len(set(source_ids)):
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS memory provenance is invalid")
        by_version[version_id] = source_ids
        _extend_unique(ranked, source_ids)
    if not set(context.selected_version_ids).issubset(by_version):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS selected context is not ranked")
    selected: list[str] = []
    for version_id in context.selected_version_ids:
        _extend_unique(selected, by_version[version_id])
    return RetrievalResponse(
        gate_retrieve=gate["retrieve"],
        ranked_source_event_ids=tuple(ranked),
        selected_source_event_ids=tuple(selected),
        context=context,
        trace=trace,
    )


def _retrieval_context(value: Any) -> RetrievalContext:
    fields = {
        "rendered",
        "tokens",
        "tokenCounterVersion",
        "tokenCountProviderInputTokens",
        "tokenCountProviderCalls",
        "considered",
        "selected",
        "truncated",
        "selectedVersionIds",
    }
    if not isinstance(value, dict) or set(value) != fields:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval context is invalid")
    rendered = value["rendered"]
    counter = value["tokenCounterVersion"]
    selected = value["selectedVersionIds"]
    if (
        not isinstance(rendered, str)
        or not rendered
        or not isinstance(counter, str)
        or not counter
        or not isinstance(selected, list)
        or not isinstance(value["truncated"], bool)
    ):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval context is invalid")
    selected_ids = tuple(_uuid(item, "context.selectedVersionId") for item in selected)
    if len(selected_ids) != len(set(selected_ids)):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS selected versions are duplicated")
    _non_negative_int(value["considered"], "context.considered")
    selected_count = _non_negative_int(value["selected"], "context.selected")
    if selected_count != len(selected_ids):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS selected count is inconsistent")
    return RetrievalContext(
        rendered=rendered,
        tokens=_positive_int(value["tokens"], "context.tokens"),
        token_counter_version=counter,
        token_count_provider_input_tokens=_non_negative_int(
            value["tokenCountProviderInputTokens"], "context.tokenCountProviderInputTokens"
        ),
        token_count_provider_calls=_non_negative_int(
            value["tokenCountProviderCalls"], "context.tokenCountProviderCalls"
        ),
        selected_version_ids=selected_ids,
    )


def _retrieval_trace(value: Any) -> RetrievalTrace:
    fields = {
        "gateReason",
        "temporalIntent",
        "componentCandidateCount",
        "fusedCandidateCount",
        "rerankOutcome",
        "embeddingProvider",
        "embeddingModelVersion",
        "embeddingInputTokens",
    }
    if not isinstance(value, dict) or set(value) != fields:
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval trace is invalid")
    for field in (
        "gateReason",
        "temporalIntent",
        "rerankOutcome",
        "embeddingProvider",
        "embeddingModelVersion",
    ):
        if not isinstance(value[field], str) or not value[field]:
            raise MemosClientError("MALFORMED_RESPONSE", "MemOS retrieval trace is invalid")
    _non_negative_int(value["componentCandidateCount"], "trace.componentCandidateCount")
    _non_negative_int(value["fusedCandidateCount"], "trace.fusedCandidateCount")
    return RetrievalTrace(
        embedding_provider=value["embeddingProvider"],
        embedding_model_version=value["embeddingModelVersion"],
        embedding_input_tokens=_non_negative_int(
            value["embeddingInputTokens"], "trace.embeddingInputTokens"
        ),
    )


def _extend_unique(output: list[str], values: tuple[str, ...]) -> None:
    for value in values:
        if value not in output:
            output.append(value)


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
    usage = _materialization_usage(payload.get("usage"))
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
    if usage.complete and status != "SUCCEEDED":
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS usage completeness is inconsistent")
    return SourceMaterializationStatus(
        source_event_id=source_event_id,
        status=status,
        created_at=created_at,
        updated_at=updated_at,
        settled_at=settled_at,
        usage=usage,
        jobs=jobs,
    )


def _materialization_usage(value: Any) -> MaterializationUsage:
    fields = {
        "complete",
        "inputTokens",
        "outputTokens",
        "embeddingTokens",
        "modelCalls",
    }
    if (
        not isinstance(value, dict)
        or set(value) != fields
        or not isinstance(value["complete"], bool)
    ):
        raise MemosClientError("MALFORMED_RESPONSE", "MemOS materialization usage is invalid")
    return MaterializationUsage(
        complete=value["complete"],
        input_tokens=_non_negative_int(value["inputTokens"], "usage.inputTokens"),
        output_tokens=_non_negative_int(value["outputTokens"], "usage.outputTokens"),
        embedding_tokens=_non_negative_int(value["embeddingTokens"], "usage.embeddingTokens"),
        model_calls=_non_negative_int(value["modelCalls"], "usage.modelCalls"),
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
