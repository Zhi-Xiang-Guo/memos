from __future__ import annotations

from typing import Any

import pytest

from memos_benchmark.baselines import (
    BaselineError,
    full_history_context,
    measure_full_history_storage,
    measure_raw_vector_storage,
    measure_rolling_summary_storage,
    prepare_raw_vector,
    prepare_rolling_summary,
    raw_vector_context,
    rolling_summary_context,
)
from memos_benchmark.ollama import ChatResult, EmbeddingResult, ProviderUsage


class FakeRuntime:
    def __init__(self, summaries: list[dict[str, Any]] | None = None) -> None:
        self.summaries = list(summaries or [])

    def chat_json(self, **_kwargs: Any) -> ChatResult:
        return ChatResult(
            value=self.summaries.pop(0),
            usage=ProviderUsage(input_tokens=5, output_tokens=3, model_calls=1),
            latency_ms=2.0,
            provider_total_ms=1.5,
            provider_load_ms=0.0,
        )

    def embed(self, *, model: str, inputs: list[str]) -> EmbeddingResult:
        del model
        vectors = []
        for value in inputs:
            if value == "alpha fact" or value == "find alpha":
                vectors.append([1.0, 0.0])
            elif value == "beta fact":
                vectors.append([0.0, 1.0])
            else:
                vectors.append([0.5, 0.5])
        tokens = sum(max(1, len(value.split())) for value in inputs)
        return EmbeddingResult(
            vectors=vectors,
            usage=ProviderUsage(embedding_tokens=tokens, model_calls=1),
            latency_ms=1.0,
            provider_total_ms=0.5,
            provider_load_ms=0.0,
        )


def _scenario() -> dict[str, Any]:
    return {
        "scenario_id": "scenario",
        "sessions": [
            {
                "session_id": "s1",
                "events": [
                    {
                        "event_id": "e1",
                        "occurred_at": "2026-01-01T00:00:00Z",
                        "actor_type": "USER",
                        "source_type": "CONVERSATION_MESSAGE",
                        "trust_level": "DIRECT_USER",
                        "content": "alpha fact",
                    }
                ],
            },
            {
                "session_id": "s2",
                "events": [
                    {
                        "event_id": "e2",
                        "occurred_at": "2026-01-02T00:00:00Z",
                        "actor_type": "USER",
                        "source_type": "CONVERSATION_MESSAGE",
                        "trust_level": "DIRECT_USER",
                        "content": "beta fact",
                    }
                ],
            },
        ],
    }


def test_full_history_keeps_recent_turns_but_renders_chronologically() -> None:
    context = full_history_context(_scenario(), "e2", FakeRuntime(), "embed", max_tokens=100)

    assert context.selected_event_ids == ["e1", "e2"]
    assert [item.content for item in context.evidence] == ["alpha fact", "beta fact"]
    assert context.usage.model_calls == 3
    assert context.context_tokens == len(context.rendered().split())


def test_raw_vector_ranks_semantic_turns_before_budget_selection() -> None:
    runtime = FakeRuntime()
    state = prepare_raw_vector(_scenario(), runtime, "embed", repetition=1)

    context = raw_vector_context(
        state,
        "find alpha",
        "e2",
        runtime,
        "embed",
        top_k=2,
        max_tokens=100,
    )

    assert context.ranked_event_ids == ["e1", "e2"]
    assert context.selected_event_ids == ["e1", "e2"]
    assert state.write_row["usage"]["embedding_tokens"] == 4


def test_rolling_summary_preserves_provenance_and_recent_turns() -> None:
    runtime = FakeRuntime(
        [
            {
                "facts": [
                    {
                        "content": "alpha",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e1"],
                    }
                ]
            },
            {
                "facts": [
                    {
                        "content": "alpha",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e1"],
                    },
                    {
                        "content": "beta",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e2"],
                    },
                ]
            },
        ]
    )
    state = prepare_rolling_summary(
        _scenario(),
        runtime,
        "chat",
        "summarize",
        {"type": "object"},
        0,
        42,
        1,
    )

    context = rolling_summary_context(state, "e2", runtime, "embed", 1, 100)

    assert len(state.write_rows) == 2
    assert context.selected_event_ids == ["e1", "e2"]
    assert [item.kind for item in context.evidence] == [
        "rolling_summary_fact",
        "rolling_summary_fact",
        "source_event",
    ]


def test_summary_cannot_cite_unseen_future_evidence() -> None:
    runtime = FakeRuntime(
        [
            {
                "facts": [
                    {
                        "content": "invented",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e2"],
                    }
                ]
            }
        ]
    )

    with pytest.raises(BaselineError, match="unknown evidence"):
        prepare_rolling_summary(
            _scenario(), runtime, "chat", "summarize", {"type": "object"}, 0, 42, 1
        )


def test_summary_enforces_schema_evidence_limits() -> None:
    runtime = FakeRuntime(
        [
            {
                "facts": [
                    {
                        "content": "invalid",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e1"] * 33,
                    }
                ]
            }
        ]
    )

    with pytest.raises(BaselineError, match="invalid or cites unknown evidence"):
        prepare_rolling_summary(
            _scenario(), runtime, "chat", "summarize", {"type": "object"}, 0, 42, 1
        )


def test_context_budget_counts_the_rendered_envelope() -> None:
    context = full_history_context(_scenario(), "e2", FakeRuntime(), "embed", max_tokens=2)

    assert context.evidence == []
    assert context.context_tokens == 2


def test_raw_vector_requires_exactly_one_query_embedding() -> None:
    class BrokenRuntime(FakeRuntime):
        def embed(self, *, model: str, inputs: list[str]) -> EmbeddingResult:
            result = super().embed(model=model, inputs=inputs)
            if inputs == ["find alpha"]:
                return EmbeddingResult(
                    vectors=[],
                    usage=result.usage,
                    latency_ms=result.latency_ms,
                    provider_total_ms=result.provider_total_ms,
                    provider_load_ms=result.provider_load_ms,
                )
            return result

    runtime = BrokenRuntime()
    state = prepare_raw_vector(_scenario(), runtime, "embed", repetition=1)

    with pytest.raises(BaselineError, match="query embedding count"):
        raw_vector_context(state, "find alpha", "e2", runtime, "embed", 2, 100)


def test_local_storage_measurements_use_declared_byte_representations() -> None:
    runtime = FakeRuntime(
        [
            {
                "facts": [
                    {
                        "content": "alpha",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e1"],
                    }
                ]
            },
            {
                "facts": [
                    {
                        "content": "beta",
                        "status": "CURRENT",
                        "time_qualifier": None,
                        "evidence_ids": ["e2"],
                    }
                ]
            },
        ]
    )
    summary = prepare_rolling_summary(
        _scenario(), runtime, "chat", "summarize", {"type": "object"}, 0, 42, 1
    )
    vectors = prepare_raw_vector(_scenario(), FakeRuntime(), "embed", repetition=1)

    history_storage = measure_full_history_storage(_scenario())
    summary_storage = measure_rolling_summary_storage(summary, recent_turns=1)
    vector_storage = measure_raw_vector_storage(vectors)

    assert history_storage["measurement_method"] == "canonical-json-utf8-retained-events-v1"
    assert history_storage["counts"] == {"events": 2}
    assert history_storage["retained_bytes"] == sum(history_storage["components"].values())
    assert summary_storage["measurement_method"] == (
        "canonical-json-utf8-final-summary-plus-recent-turns-v1"
    )
    assert summary_storage["counts"] == {"facts": 1, "recent_events": 1}
    assert summary_storage["retained_bytes"] == sum(summary_storage["components"].values())
    assert vector_storage["measurement_method"] == (
        "canonical-json-utf8-events-plus-dense-float32-le-v1"
    )
    assert vector_storage["components"]["dense_float32_le"] == 16
    assert vector_storage["counts"] == {
        "events": 2,
        "vectors": 2,
        "vector_dimensions": 4,
    }
    assert vector_storage["retained_bytes"] == sum(vector_storage["components"].values())
