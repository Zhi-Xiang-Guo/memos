from __future__ import annotations

import io
import json
from unittest.mock import patch

import pytest

from memos_benchmark.client import MemosClient, MemosClientError

SOURCE_ID = "00000000-0000-0000-0000-000000000010"
JOB_ID = "00000000-0000-0000-0000-000000000020"
NOW = "2026-08-30T00:00:00Z"


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
    assert request.full_url.endswith(f"/v1/source-events/{SOURCE_ID}/materialization")
    assert request.headers["Authorization"] == "Bearer token-value"


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
