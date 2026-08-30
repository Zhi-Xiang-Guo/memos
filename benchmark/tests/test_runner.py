from __future__ import annotations

import base64
import json
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any
from uuid import UUID

import pytest

from memos_benchmark.ollama import ChatResult, EmbeddingResult, ProviderUsage
from memos_benchmark.runner import (
    RunnerError,
    RunnerSettings,
    UnifiedBenchmarkRunner,
    create_hs256_token,
    validate_answer_output,
)

EMBEDDING_DIGEST = "b" * 64
SOURCE_ID = "00000000-0000-0000-0000-000000000010"
VERSION_ID = "00000000-0000-0000-0000-000000000020"


class FakeRuntime:
    def chat_json(self, **kwargs: Any) -> ChatResult:
        schema = kwargs["schema"]
        if "facts" in schema.get("properties", {}):
            value = {"facts": []}
        else:
            value = {
                "abstain": True,
                "answer": "",
                "items": [],
                "reason_code": "test",
                "citations": [],
            }
        return ChatResult(
            value=value,
            usage=ProviderUsage(input_tokens=5, output_tokens=2, model_calls=1),
            latency_ms=1.0,
            provider_total_ms=0.8,
            provider_load_ms=0.0,
        )

    def embed(self, *, model: str, inputs: list[str]) -> EmbeddingResult:
        del model
        tokens = sum(max(1, len(value.split())) for value in inputs)
        return EmbeddingResult(
            vectors=[[1.0, 0.0] for _ in inputs],
            usage=ProviderUsage(embedding_tokens=tokens, model_calls=1),
            latency_ms=1.0,
            provider_total_ms=0.8,
            provider_load_ms=0.0,
        )


class FakeMemosClient:
    def __init__(self) -> None:
        self.storage_calls = 0

    def storage_observation(self, bearer_token: str, **kwargs: Any) -> Any:
        del bearer_token, kwargs
        self.storage_calls += 1
        if self.storage_calls == 1:
            return SimpleNamespace(
                scope_row_count=0,
                scope_row_bytes=0,
                relations=(),
                database_table_bytes=1_000,
                database_index_bytes=2_000,
                database_total_bytes=3_000,
            )
        return SimpleNamespace(
            scope_row_count=3,
            scope_row_bytes=400,
            relations=(
                SimpleNamespace(relation="memos.outbox_job", row_count=2, row_bytes=240),
                SimpleNamespace(relation="memos.source_event", row_count=1, row_bytes=160),
            ),
            database_table_bytes=1_100,
            database_index_bytes=2_200,
            database_total_bytes=3_300,
        )

    def ingest_source_event(
        self,
        event: dict[str, Any],
        bearer_token: str,
        idempotency_key: str,
        timeout_seconds: float = 10.0,
    ) -> Any:
        del bearer_token, idempotency_key, timeout_seconds
        return SimpleNamespace(
            source_event_id=SOURCE_ID,
            source_id=event["sourceId"],
            disposition="ACCEPTED",
        )

    def wait_for_materialization(
        self, source_event_id: str, bearer_token: str, **kwargs: Any
    ) -> Any:
        del source_event_id, bearer_token, kwargs
        now = datetime(2026, 8, 30, tzinfo=UTC)
        return SimpleNamespace(
            usage=SimpleNamespace(
                complete=True,
                input_tokens=10,
                output_tokens=4,
                embedding_tokens=3,
                model_calls=2,
            ),
            created_at=now,
            settled_at=now,
        )

    def retrieval_trace(self, query: str, bearer_token: str, **kwargs: Any) -> Any:
        del query, bearer_token, kwargs
        rendered = SOURCE_ID
        return SimpleNamespace(
            context=SimpleNamespace(
                rendered=rendered,
                tokens=1,
                token_counter_version="sha256:" + EMBEDDING_DIGEST,
                token_count_provider_input_tokens=2,
                token_count_provider_calls=1,
            ),
            trace=SimpleNamespace(
                embedding_provider="ollama",
                embedding_model_version="sha256:" + EMBEDDING_DIGEST,
                embedding_input_tokens=1,
            ),
            ranked_source_event_ids=(SOURCE_ID,),
            selected_source_event_ids=(SOURCE_ID,),
        )


def _manifest() -> dict[str, Any]:
    chat = {"tag": "chat", "ollama_model_id": "a" * 64}
    return {
        "baselines": ["full_history", "rolling_summary", "raw_turn_vector", "memos"],
        "selected_models": {
            "extractor": chat,
            "summary": chat,
            "answer": chat,
            "embedding": {"tag": "embed", "ollama_model_id": EMBEDDING_DIGEST},
            "reranker": None,
            "judge": None,
        },
        "sampling": {"temperature": 0, "seed": 42},
        "limits": {
            "evidence_tokens": 1200,
            "summary_recent_turns": 2,
            "vector_top_k": 8,
            "retrieval_limit": 8,
        },
    }


def _scenario() -> dict[str, Any]:
    return {
        "scenario_id": "dev-one",
        "split": "dev",
        "scope": {"tenant_id": "tenant", "user_id": "user", "agent_id": "agent"},
        "sessions": [
            {
                "session_id": "session",
                "events": [
                    {
                        "event_id": "event-1",
                        "occurred_at": "2026-08-30T00:00:00Z",
                        "actor_type": "USER",
                        "source_type": "CONVERSATION_MESSAGE",
                        "trust_level": "DIRECT_USER",
                        "content": "Remember blue.",
                    }
                ],
            }
        ],
        "questions": [
            {
                "question_id": "question-1",
                "after_event_id": "event-1",
                "query": "Which color?",
            }
        ],
    }


def _settings() -> RunnerSettings:
    return RunnerSettings(
        run_id="test-run",
        split="dev",
        campaign_kind="SMOKE",
        repetitions=1,
        jwt_issuer="issuer",
        jwt_audience="audience",
        jwt_secret=b"a-secret-with-at-least-thirty-two-bytes",
        settle_timeout_seconds=10,
        poll_interval_seconds=0.1,
    )


def test_unified_runner_emits_every_four_baseline_execution_row() -> None:
    ticks = iter(range(0, 1_000_000_000, 1_000_000))
    runner = UnifiedBenchmarkRunner(
        runtime=FakeRuntime(),
        memos=FakeMemosClient(),
        dataset_manifest=_manifest(),
        scenarios=[_scenario()],
        answer_prompt="answer",
        answer_schema={"type": "object", "properties": {"citations": {}}},
        summary_prompt="summary",
        summary_schema={"type": "object", "properties": {"facts": {}}},
        settings=_settings(),
        monotonic_ns=lambda: next(ticks),
        now_seconds=lambda: 1_700_000_000,
    )

    rows = runner.execute()

    assert [row["baseline"] for row in rows.writes] == [
        "full_history",
        "memos",
        "raw_turn_vector",
        "rolling_summary",
    ]
    assert len(rows.retrieval) == len(rows.answers) == len(rows.timings) == 4
    assert all(row["status"] == "SUCCESS" for row in rows.answers)
    assert all(row["usage_complete"] is True for row in rows.retrieval)
    memos_write = next(row for row in rows.writes if row["baseline"] == "memos")
    assert memos_write["usage"] == {
        "input_tokens": 10,
        "output_tokens": 4,
        "embedding_tokens": 3,
        "model_calls": 2,
    }
    assert memos_write["storage"] == {
        "complete": True,
        "measurement_method": (
            "postgresql-pg-column-size-scope-rows-plus-native-relation-delta-v1"
        ),
        "retained_bytes": 400,
        "components": {"memos.outbox_job": 240, "memos.source_event": 160},
        "counts": {"memos.outbox_job": 2, "memos.source_event": 1},
        "database_native": {
            "before": {"table_bytes": 1_000, "index_bytes": 2_000, "total_bytes": 3_000},
            "after": {"table_bytes": 1_100, "index_bytes": 2_200, "total_bytes": 3_300},
            "delta": {"table_bytes": 100, "index_bytes": 200, "total_bytes": 300},
        },
    }
    assert all("storage" in row for row in rows.writes)
    memos_retrieval = next(row for row in rows.retrieval if row["baseline"] == "memos")
    assert memos_retrieval["selected_event_ids"] == ["event-1"]
    assert memos_retrieval["tokenizer_verification_tokens"] == 1


def test_answer_validation_maps_memos_citations_and_rejects_unknown_values() -> None:
    output = {
        "abstain": False,
        "answer": "blue",
        "items": [],
        "reason_code": "",
        "citations": [SOURCE_ID],
    }

    mapped = validate_answer_output(output, [SOURCE_ID], {SOURCE_ID: "event-1"})

    assert mapped["citations"] == ["event-1"]
    with pytest.raises(RunnerError, match="outside the context"):
        validate_answer_output(
            {**output, "citations": [str(UUID(int=99))]},
            [SOURCE_ID],
            {SOURCE_ID: "event-1"},
        )


def test_generated_hs256_token_contains_exact_scope_and_roles() -> None:
    token = create_hs256_token(
        tenant="tenant",
        user="user",
        agent="agent",
        subject="subject",
        roles=["USER", "OPERATOR"],
        issuer="issuer",
        audience="audience",
        secret=b"a-secret-with-at-least-thirty-two-bytes",
        now=100,
        lifetime_seconds=900,
    )
    payload = token.split(".")[1]
    decoded = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))

    assert decoded["tenant_id"] == "tenant"
    assert decoded["roles"] == ["USER", "OPERATOR"]
    assert decoded["exp"] == 1000
