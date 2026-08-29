"""Fair-context builders for the non-MemOS baselines."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Protocol

from memos_benchmark.artifacts import canonical_json
from memos_benchmark.ollama import ChatResult, EmbeddingResult, ProviderUsage


class BaselineError(ValueError):
    """Raised when a baseline cannot construct an eligible context."""


class ModelRuntime(Protocol):
    def chat_json(
        self,
        *,
        model: str,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        temperature: float,
        seed: int,
    ) -> ChatResult: ...

    def embed(self, *, model: str, inputs: list[str]) -> EmbeddingResult: ...


@dataclass(frozen=True)
class Evidence:
    event_ids: tuple[str, ...]
    kind: str
    content: str
    metadata: dict[str, Any]

    def rendered(self) -> str:
        return canonical_json(
            {
                "event_ids": list(self.event_ids),
                "kind": self.kind,
                "content": self.content,
                "metadata": self.metadata,
                "trust": "untrusted_data",
            }
        )


@dataclass(frozen=True)
class ContextResult:
    evidence: list[Evidence]
    ranked_event_ids: list[str]
    selected_event_ids: list[str]
    context_tokens: int
    usage: ProviderUsage
    latency_ms: float

    def rendered(self) -> str:
        return _render_evidence(self.evidence)


@dataclass(frozen=True)
class VectorState:
    events: list[dict[str, Any]]
    vectors: list[list[float]]
    write_row: dict[str, Any]


@dataclass(frozen=True)
class SummarySnapshot:
    end_position: int
    facts: list[dict[str, Any]]


@dataclass(frozen=True)
class SummaryState:
    events: list[dict[str, Any]]
    positions: dict[str, int]
    snapshots: list[SummarySnapshot]
    write_rows: list[dict[str, Any]]


def events_through(scenario: dict[str, Any], cutoff_event_id: str) -> list[dict[str, Any]]:
    events = _flatten_events(scenario)
    for index, event in enumerate(events):
        if event["event_id"] == cutoff_event_id:
            return events[: index + 1]
    raise BaselineError(f"cutoff event is absent from scenario: {cutoff_event_id}")


def full_history_context(
    scenario: dict[str, Any],
    cutoff_event_id: str,
    runtime: ModelRuntime,
    embedding_model: str,
    max_tokens: int,
) -> ContextResult:
    """Keep the most recent complete turns, then render them chronologically."""

    events = events_through(scenario, cutoff_event_id)
    candidates = [_event_evidence(event) for event in reversed(events)]
    selected, tokens, usage, latency = _select_under_budget(
        candidates, runtime, embedding_model, max_tokens
    )
    selected.reverse()
    return ContextResult(
        evidence=selected,
        ranked_event_ids=[event["event_id"] for event in events],
        selected_event_ids=_unique_event_ids(selected),
        context_tokens=tokens,
        usage=usage,
        latency_ms=latency,
    )


def prepare_raw_vector(
    scenario: dict[str, Any],
    runtime: ModelRuntime,
    embedding_model: str,
    repetition: int,
) -> VectorState:
    events = _flatten_events(scenario)
    result = runtime.embed(model=embedding_model, inputs=[event["content"] for event in events])
    if len(result.vectors) != len(events):
        raise BaselineError("raw-turn embedding count differs from source events")
    write_row = {
        "baseline": "raw_turn_vector",
        "scenario_id": scenario["scenario_id"],
        "repetition": repetition,
        "stage": "embed_source_turns",
        "status": "SUCCESS",
        "event_ids": [event["event_id"] for event in events],
        "usage": result.usage.as_dict(),
        "latency_ms": result.latency_ms,
        "provider_total_ms": result.provider_total_ms,
        "provider_load_ms": result.provider_load_ms,
    }
    return VectorState(events=events, vectors=result.vectors, write_row=write_row)


def raw_vector_context(
    state: VectorState,
    query: str,
    cutoff_event_id: str,
    runtime: ModelRuntime,
    embedding_model: str,
    top_k: int,
    max_tokens: int,
) -> ContextResult:
    if top_k < 1:
        raise BaselineError("vector top_k must be positive")
    cutoff = _position(state.events, cutoff_event_id)
    query_result = runtime.embed(model=embedding_model, inputs=[query])
    if len(query_result.vectors) != 1:
        raise BaselineError("query embedding count differs from one")
    query_vector = query_result.vectors[0]
    ranked_positions = sorted(
        range(cutoff + 1),
        key=lambda index: (-_cosine(query_vector, state.vectors[index]), index),
    )[:top_k]
    candidates = [_event_evidence(state.events[index]) for index in ranked_positions]
    selected, tokens, budget_usage, budget_latency = _select_under_budget(
        candidates, runtime, embedding_model, max_tokens
    )
    return ContextResult(
        evidence=selected,
        ranked_event_ids=[state.events[index]["event_id"] for index in ranked_positions],
        selected_event_ids=_unique_event_ids(selected),
        context_tokens=tokens,
        usage=query_result.usage + budget_usage,
        latency_ms=round(query_result.latency_ms + budget_latency, 3),
    )


def prepare_rolling_summary(
    scenario: dict[str, Any],
    runtime: ModelRuntime,
    summary_model: str,
    summary_prompt: str,
    summary_schema: dict[str, Any],
    temperature: float,
    seed: int,
    repetition: int,
) -> SummaryState:
    events = _flatten_events(scenario)
    positions = {event["event_id"]: index for index, event in enumerate(events)}
    seen_event_ids: set[str] = set()
    facts: list[dict[str, Any]] = []
    snapshots: list[SummarySnapshot] = []
    write_rows: list[dict[str, Any]] = []
    for session in scenario["sessions"]:
        session_events = session["events"]
        seen_event_ids.update(event["event_id"] for event in session_events)
        user_payload = canonical_json(
            {
                "previous_summary": {"facts": facts},
                "session": {
                    "session_id": session["session_id"],
                    "events": [
                        {
                            "event_id": event["event_id"],
                            "occurred_at": event["occurred_at"],
                            "actor_type": event["actor_type"],
                            "trust_level": event["trust_level"],
                            "content": event["content"],
                        }
                        for event in session_events
                    ],
                },
            }
        )
        result = runtime.chat_json(
            model=summary_model,
            messages=[
                {"role": "system", "content": summary_prompt},
                {"role": "user", "content": user_payload},
            ],
            schema=summary_schema,
            temperature=temperature,
            seed=seed,
        )
        facts = _validate_summary(result.value, seen_event_ids)
        end_position = positions[session_events[-1]["event_id"]]
        snapshots.append(SummarySnapshot(end_position=end_position, facts=facts))
        write_rows.append(
            {
                "baseline": "rolling_summary",
                "scenario_id": scenario["scenario_id"],
                "repetition": repetition,
                "stage": "summarize_session",
                "session_id": session["session_id"],
                "status": "SUCCESS",
                "event_ids": [event["event_id"] for event in session_events],
                "usage": result.usage.as_dict(),
                "latency_ms": result.latency_ms,
                "provider_total_ms": result.provider_total_ms,
                "provider_load_ms": result.provider_load_ms,
            }
        )
    return SummaryState(
        events=events,
        positions=positions,
        snapshots=snapshots,
        write_rows=write_rows,
    )


def rolling_summary_context(
    state: SummaryState,
    cutoff_event_id: str,
    runtime: ModelRuntime,
    embedding_model: str,
    recent_turns: int,
    max_tokens: int,
) -> ContextResult:
    cutoff = state.positions.get(cutoff_event_id)
    if cutoff is None:
        raise BaselineError(f"summary cutoff event is absent: {cutoff_event_id}")
    eligible_snapshots = [
        snapshot for snapshot in state.snapshots if snapshot.end_position <= cutoff
    ]
    facts = eligible_snapshots[-1].facts if eligible_snapshots else []
    recent_events = state.events[: cutoff + 1][-recent_turns:] if recent_turns else []
    recent = [_event_evidence(event) for event in reversed(recent_events)]
    summary = [_summary_evidence(fact) for fact in facts]
    selected_priority, tokens, usage, latency = _select_under_budget(
        [*recent, *summary], runtime, embedding_model, max_tokens
    )
    selected_ids = {id(value) for value in selected_priority}
    selected = [value for value in summary if id(value) in selected_ids]
    selected.extend(reversed([value for value in recent if id(value) in selected_ids]))
    return ContextResult(
        evidence=selected,
        ranked_event_ids=_unique_event_ids([*summary, *reversed(recent)]),
        selected_event_ids=_unique_event_ids(selected),
        context_tokens=tokens,
        usage=usage,
        latency_ms=latency,
    )


def _validate_summary(value: dict[str, Any], allowed_event_ids: set[str]) -> list[dict[str, Any]]:
    if set(value) != {"facts"} or not isinstance(value["facts"], list):
        raise BaselineError("summary output has an unexpected schema")
    if len(value["facts"]) > 64:
        raise BaselineError("summary output exceeds the fact limit")
    facts: list[dict[str, Any]] = []
    seen: set[str] = set()
    for fact in value["facts"]:
        if not isinstance(fact, dict) or set(fact) != {
            "content",
            "status",
            "time_qualifier",
            "evidence_ids",
        }:
            raise BaselineError("summary fact has an unexpected schema")
        content = fact["content"]
        status = fact["status"]
        qualifier = fact["time_qualifier"]
        evidence_ids = fact["evidence_ids"]
        if (
            not isinstance(content, str)
            or not content
            or len(content) > 2048
            or status not in {"CURRENT", "HISTORICAL", "CONFLICTED"}
            or (qualifier is not None and (not isinstance(qualifier, str) or len(qualifier) > 256))
            or not isinstance(evidence_ids, list)
            or not evidence_ids
            or len(evidence_ids) > 32
            or any(not isinstance(value, str) or not value for value in evidence_ids)
            or any(len(value) > 200 for value in evidence_ids)
            or len(evidence_ids) != len(set(evidence_ids))
            or not set(evidence_ids).issubset(allowed_event_ids)
        ):
            raise BaselineError("summary fact is invalid or cites unknown evidence")
        identity = canonical_json(fact)
        if identity in seen:
            raise BaselineError("summary contains duplicate facts")
        seen.add(identity)
        facts.append(fact)
    return facts


def _select_under_budget(
    candidates: list[Evidence],
    runtime: ModelRuntime,
    embedding_model: str,
    max_tokens: int,
) -> tuple[list[Evidence], int, ProviderUsage, float]:
    if max_tokens < 1:
        raise BaselineError("evidence token budget must be positive")
    selected: list[Evidence] = []
    usage = ProviderUsage()
    latency = 0.0
    empty_count = runtime.embed(model=embedding_model, inputs=[_render_evidence([])])
    used = empty_count.usage.embedding_tokens
    usage += empty_count.usage
    latency += empty_count.latency_ms
    if used <= 0:
        raise BaselineError("token counter returned no tokens")
    if used > max_tokens:
        raise BaselineError("evidence token budget cannot contain the context envelope")
    for candidate in candidates:
        tentative = [*selected, candidate]
        count = runtime.embed(model=embedding_model, inputs=[_render_evidence(tentative)])
        tokens = count.usage.embedding_tokens
        usage += count.usage
        latency += count.latency_ms
        if tokens <= 0:
            raise BaselineError("token counter returned no tokens")
        if tokens <= max_tokens:
            selected = tentative
            used = tokens
    return selected, used, usage, round(latency, 3)


def _flatten_events(scenario: dict[str, Any]) -> list[dict[str, Any]]:
    return [event for session in scenario["sessions"] for event in session["events"]]


def _event_evidence(event: dict[str, Any]) -> Evidence:
    return Evidence(
        event_ids=(event["event_id"],),
        kind="source_event",
        content=event["content"],
        metadata={
            "occurred_at": event["occurred_at"],
            "actor_type": event["actor_type"],
            "source_type": event["source_type"],
            "trust_level": event["trust_level"],
        },
    )


def _summary_evidence(fact: dict[str, Any]) -> Evidence:
    return Evidence(
        event_ids=tuple(fact["evidence_ids"]),
        kind="rolling_summary_fact",
        content=fact["content"],
        metadata={"status": fact["status"], "time_qualifier": fact["time_qualifier"]},
    )


def _unique_event_ids(evidence: list[Evidence]) -> list[str]:
    output: list[str] = []
    seen: set[str] = set()
    for item in evidence:
        for event_id in item.event_ids:
            if event_id not in seen:
                seen.add(event_id)
                output.append(event_id)
    return output


def _render_evidence(evidence: list[Evidence]) -> str:
    return "[\n" + ",\n".join(item.rendered() for item in evidence) + "\n]"


def _position(events: list[dict[str, Any]], event_id: str) -> int:
    for index, event in enumerate(events):
        if event["event_id"] == event_id:
            return index
    raise BaselineError(f"event is absent from vector state: {event_id}")


def _cosine(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or not left:
        raise BaselineError("embedding dimensions differ")
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return sum(a * b for a, b in zip(left, right, strict=True)) / (left_norm * right_norm)
