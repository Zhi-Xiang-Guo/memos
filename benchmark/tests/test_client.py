from __future__ import annotations

import io
import json
from unittest.mock import patch

import pytest

from memos_benchmark.client import STORAGE_RELATIONS, MemosClient, MemosClientError

SOURCE_ID = "00000000-0000-0000-0000-000000000010"
JOB_ID = "00000000-0000-0000-0000-000000000020"
NOW = "2026-08-30T00:00:00Z"
VERSION_ID = "00000000-0000-0000-0000-000000000030"


class FakeResponse(io.BytesIO):
    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


def _response(value: object) -> FakeResponse:
    return FakeResponse(json.dumps(value).encode())


def _materialization(status: str, job_state: str) -> dict[str, object]:
    terminal = job_state in {"SUCCEEDED", "DEAD"}
    return {
        "sourceEventId": SOURCE_ID,
        "status": status,
        "createdAt": NOW,
        "updatedAt": NOW,
        "settledAt": NOW if status != "PROCESSING" else None,
        "usage": {
            "complete": status == "SUCCEEDED",
            "inputTokens": 11,
            "outputTokens": 7,
            "embeddingTokens": 5,
            "modelCalls": 2,
        },
        "jobs": [
            {
                "jobId": JOB_ID,
                "sourceEventId": SOURCE_ID,
                "jobType": "MATERIALIZE_SOURCE",
                "state": job_state,
                "attempt": 1,
                "maxAttempts": 5,
                "nextAttemptAt": NOW if job_state in {"PENDING", "RETRY_WAIT"} else None,
                "leaseExpiresAt": NOW if job_state == "CLAIMED" else None,
                "errorClass": "PROVIDER_FAILURE" if job_state == "DEAD" else None,
                "replayCount": 0,
                "completedAt": NOW if terminal else None,
                "createdAt": NOW,
                "updatedAt": NOW,
            }
        ],
    }


def _retrieval() -> dict[str, object]:
    return {
        "gate": {"retrieve": True, "reason": "MEMORY_QUERY"},
        "intent": {"temporal": "PRESENT", "targetTime": None},
        "context": {
            "rendered": "[\n{}\n]",
            "tokens": 4,
            "tokenCounterVersion": "sha256:" + "b" * 64,
            "tokenCountProviderInputTokens": 9,
            "tokenCountProviderCalls": 2,
            "considered": 1,
            "selected": 1,
            "truncated": False,
            "selectedVersionIds": [VERSION_ID],
        },
        "memories": [
            {
                "memoryId": "00000000-0000-0000-0000-000000000040",
                "versionId": VERSION_ID,
                "memoryType": "SEMANTIC",
                "subjectKind": "USER",
                "subjectLabel": "user",
                "predicate": "preference.theme",
                "status": "CURRENT",
                "normalizedContent": "dark",
                "validFrom": None,
                "validTo": None,
                "recordedAt": NOW,
                "sourceEventIds": [SOURCE_ID],
                "fusedScore": 1.0,
                "rerankRank": None,
                "watermark": {},
                "components": [],
            }
        ],
        "trace": {
            "gateReason": "MEMORY_QUERY",
            "temporalIntent": "PRESENT",
            "componentCandidateCount": 1,
            "fusedCandidateCount": 1,
            "rerankOutcome": "DISABLED",
            "embeddingProvider": "ollama",
            "embeddingModelVersion": "sha256:" + "b" * 64,
            "embeddingInputTokens": 6,
        },
    }


def test_readiness_uses_public_health_contract() -> None:
    with patch("memos_benchmark.client.urlopen", return_value=_response({"status": "UP"})) as call:
        status = MemosClient("http://example.test/").readiness()

    request = call.call_args.args[0]
    assert status.status == "UP"
    assert request.full_url == "http://example.test/readyz"
    assert request.headers["Accept"] == "application/json"
    assert call.call_args.kwargs["timeout"] == 5.0


def test_source_materialization_is_authenticated_and_strictly_decoded() -> None:
    with patch(
        "memos_benchmark.client.urlopen",
        return_value=_response(_materialization("SUCCEEDED", "SUCCEEDED")),
    ) as call:
        observed = MemosClient("http://example.test").source_materialization(
            SOURCE_ID, "token-value"
        )

    request = call.call_args.args[0]
    assert observed.status == "SUCCEEDED"
    assert observed.jobs[0].job_type == "MATERIALIZE_SOURCE"
    assert observed.jobs[0].completed_at is not None
    assert observed.usage.complete is True
    assert observed.usage.model_calls == 2
    assert request.full_url.endswith(f"/v1/source-events/{SOURCE_ID}/materialization")
    assert request.headers["Authorization"] == "Bearer token-value"


def test_storage_observation_is_authenticated_content_free_and_strictly_decoded() -> None:
    relation_values = {
        "memos.outbox_job": (2, 240),
        "memos.source_event": (1, 160),
    }
    payload = {
        "schemaVersion": "memos-storage-observation.v1",
        "scope": {
            "rowCount": 3,
            "rowBytes": 400,
            "relations": [
                {
                    "relation": relation,
                    "rowCount": relation_values.get(relation, (0, 0))[0],
                    "rowBytes": relation_values.get(relation, (0, 0))[1],
                }
                for relation in STORAGE_RELATIONS
            ],
        },
        "database": {"tableBytes": 16_384, "indexBytes": 8_192, "totalBytes": 24_576},
    }
    with patch("memos_benchmark.client.urlopen", return_value=_response(payload)) as call:
        observed = MemosClient("http://example.test").storage_observation("operator-token")

    request = call.call_args.args[0]
    assert request.full_url.endswith("/v1/operations/storage")
    assert request.headers["Authorization"] == "Bearer operator-token"
    assert observed.scope_row_count == 3
    assert observed.scope_row_bytes == 400
    assert tuple(value.relation for value in observed.relations) == STORAGE_RELATIONS
    assert observed.database_total_bytes == 24_576


def test_storage_observation_rejects_an_incomplete_relation_contract() -> None:
    payload = {
        "schemaVersion": "memos-storage-observation.v1",
        "scope": {
            "rowCount": 0,
            "rowBytes": 0,
            "relations": [
                {"relation": relation, "rowCount": 0, "rowBytes": 0}
                for relation in STORAGE_RELATIONS[:-1]
            ],
        },
        "database": {"tableBytes": 100, "indexBytes": 200, "totalBytes": 300},
    }
    with (
        patch("memos_benchmark.client.urlopen", return_value=_response(payload)),
        pytest.raises(MemosClientError, match="relation contract"),
    ):
        MemosClient("http://example.test").storage_observation("operator-token")


def test_wait_uses_observable_state_instead_of_a_fixed_delay() -> None:
    now = [0.0]

    def sleep(seconds: float) -> None:
        now[0] += seconds

    responses = [
        _response(_materialization("PROCESSING", "PENDING")),
        _response(_materialization("SUCCEEDED", "SUCCEEDED")),
    ]
    with patch("memos_benchmark.client.urlopen", side_effect=responses) as call:
        observed = MemosClient(
            "http://example.test", monotonic=lambda: now[0], sleeper=sleep
        ).wait_for_materialization(
            SOURCE_ID,
            "token-value",
            settle_timeout_seconds=3,
            poll_interval_seconds=0.25,
        )

    assert observed.status == "SUCCEEDED"
    assert now[0] == 0.25
    assert call.call_count == 2


def test_ingest_source_event_sends_idempotency_and_decodes_receipt() -> None:
    payload = {
        "sourceEventId": SOURCE_ID,
        "sourceId": "dataset-e1",
        "materializationJobId": JOB_ID,
        "disposition": "ACCEPTED",
        "acceptedAt": NOW,
        "materializationState": "PENDING",
    }
    event = {
        "sourceId": "dataset-e1",
        "sessionId": "session-1",
        "actorType": "USER",
        "sourceType": "CONVERSATION_MESSAGE",
        "trustLevel": "DIRECT_USER",
        "occurredAt": NOW,
        "payload": {"content": "hello"},
    }
    with patch("memos_benchmark.client.urlopen", return_value=_response(payload)) as call:
        receipt = MemosClient("http://example.test").ingest_source_event(
            event, "token-value", "idem-1"
        )

    request = call.call_args.args[0]
    assert receipt.source_event_id == SOURCE_ID
    assert request.method == "POST"
    assert request.headers["Idempotency-key"] == "idem-1"
    assert json.loads(request.data) == event


def test_retrieval_trace_decodes_provenance_and_token_usage() -> None:
    with patch("memos_benchmark.client.urlopen", return_value=_response(_retrieval())) as call:
        result = MemosClient("http://example.test").retrieval_trace(
            "theme?", "operator-token", limit=8, max_tokens=1200
        )

    body = json.loads(call.call_args.args[0].data)
    assert body["mode"] == "HYBRID"
    assert result.ranked_source_event_ids == (SOURCE_ID,)
    assert result.selected_source_event_ids == (SOURCE_ID,)
    assert result.context.tokens == 4
    assert result.trace.embedding_input_tokens == 6


def test_retrieval_trace_rejects_selected_versions_outside_ranked_memories() -> None:
    response = _retrieval()
    response["context"] = {
        **response["context"],
        "selectedVersionIds": ["00000000-0000-0000-0000-000000000099"],
    }
    with (
        patch("memos_benchmark.client.urlopen", return_value=_response(response)),
        pytest.raises(MemosClientError) as error,
    ):
        MemosClient("http://example.test").retrieval_trace(
            "theme?", "operator-token", limit=8, max_tokens=1200
        )

    assert error.value.kind == "MALFORMED_RESPONSE"


@pytest.mark.parametrize(
    ("response", "expected_kind"),
    [
        (_materialization("FAILED", "DEAD"), "MATERIALIZATION_FAILED"),
        ({**_materialization("SUCCEEDED", "PENDING"), "settledAt": NOW}, "MALFORMED_RESPONSE"),
        (_materialization("PROCESSING", "DEAD"), "MALFORMED_RESPONSE"),
        ({**_materialization("FAILED", "PENDING"), "settledAt": NOW}, "MALFORMED_RESPONSE"),
    ],
)
def test_wait_rejects_failed_or_inconsistent_terminal_state(
    response: dict[str, object], expected_kind: str
) -> None:
    with (
        patch("memos_benchmark.client.urlopen", return_value=_response(response)),
        pytest.raises(MemosClientError) as error,
    ):
        MemosClient("http://example.test").wait_for_materialization(
            SOURCE_ID, "token-value", settle_timeout_seconds=1
        )

    assert error.value.kind == expected_kind


def test_wait_has_a_total_deadline() -> None:
    now = [0.0]

    def sleep(seconds: float) -> None:
        now[0] += seconds

    with (
        patch(
            "memos_benchmark.client.urlopen",
            return_value=_response(_materialization("PROCESSING", "PENDING")),
        ) as call,
        pytest.raises(MemosClientError) as error,
    ):
        MemosClient(
            "http://example.test", monotonic=lambda: now[0], sleeper=sleep
        ).wait_for_materialization(
            SOURCE_ID,
            "token-value",
            settle_timeout_seconds=0.5,
            poll_interval_seconds=1,
        )

    assert error.value.kind == "MATERIALIZATION_TIMEOUT"
    assert call.call_count == 1


def test_source_materialization_rejects_duplicate_or_unordered_jobs() -> None:
    source_job = _materialization("PROCESSING", "SUCCEEDED")["jobs"][0]
    projection_job = {
        **source_job,
        "jobId": "00000000-0000-0000-0000-000000000021",
        "jobType": "PROJECTION_BUILD",
        "state": "PENDING",
        "attempt": 0,
        "nextAttemptAt": NOW,
        "completedAt": None,
    }
    for jobs in ([source_job, source_job], [projection_job, source_job]):
        response = {**_materialization("PROCESSING", "PENDING"), "jobs": jobs}
        with (
            patch("memos_benchmark.client.urlopen", return_value=_response(response)),
            pytest.raises(MemosClientError) as error,
        ):
            MemosClient("http://example.test").source_materialization(SOURCE_ID, "token-value")

        assert error.value.kind == "MALFORMED_RESPONSE"


def test_source_materialization_rejects_missing_or_inconsistent_usage() -> None:
    missing = _materialization("SUCCEEDED", "SUCCEEDED")
    missing.pop("usage")
    inconsistent = _materialization("PROCESSING", "PENDING")
    inconsistent["usage"] = {**inconsistent["usage"], "complete": True}
    for response in (missing, inconsistent):
        with (
            patch("memos_benchmark.client.urlopen", return_value=_response(response)),
            pytest.raises(MemosClientError) as error,
        ):
            MemosClient("http://example.test").source_materialization(SOURCE_ID, "token-value")

        assert error.value.kind == "MALFORMED_RESPONSE"


def test_client_errors_do_not_include_transport_details() -> None:
    class FailingCall:
        def __call__(self, *_args: object, **_kwargs: object) -> FakeResponse:
            raise OSError("raw secret response")

    with (
        patch("memos_benchmark.client.urlopen", new=FailingCall()),
        pytest.raises(MemosClientError) as error,
    ):
        MemosClient("http://example.test").source_materialization(SOURCE_ID, "token-value")

    assert error.value.kind == "TRANSPORT"
    assert "secret" not in str(error.value)
