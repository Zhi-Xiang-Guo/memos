"""Unified four-baseline runner for the frozen MemOS smoke contract."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import platform
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

from memos_benchmark.artifacts import (
    ArtifactPackageError,
    build_run_manifest,
    canonical_json,
    expected_case_rows,
    generate_costs,
    verify_package,
    write_key,
    write_package,
)
from memos_benchmark.baselines import (
    BaselineError,
    ContextResult,
    SummaryState,
    VectorState,
    full_history_context,
    prepare_raw_vector,
    prepare_rolling_summary,
    raw_vector_context,
    rolling_summary_context,
)
from memos_benchmark.client import MemosClient, MemosClientError, SourceMaterializationStatus
from memos_benchmark.dataset import BenchmarkDatasetError, load_dataset
from memos_benchmark.metrics import BenchmarkMetricError, execution_key, generate_metrics
from memos_benchmark.ollama import OllamaClient, OllamaError, ProviderUsage

BASELINES = {"full_history", "rolling_summary", "raw_turn_vector", "memos"}
ZERO_USAGE = ProviderUsage()


class RunnerError(RuntimeError):
    """Content-safe runner failure."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind


class BenchmarkRuntime(Protocol):
    def inspect(self, selected_models: dict[str, Any]) -> dict[str, Any]: ...

    def chat_json(self, **kwargs: Any) -> Any: ...

    def embed(self, **kwargs: Any) -> Any: ...


class BenchmarkMemosClient(Protocol):
    def readiness(self, timeout_seconds: float = 5.0) -> Any: ...

    def ingest_source_event(
        self,
        event: dict[str, Any],
        bearer_token: str,
        idempotency_key: str,
        timeout_seconds: float = 10.0,
    ) -> Any: ...

    def wait_for_materialization(
        self, source_event_id: str, bearer_token: str, **kwargs: Any
    ) -> Any: ...

    def retrieval_trace(self, query: str, bearer_token: str, **kwargs: Any) -> Any: ...


@dataclass(frozen=True)
class RunnerSettings:
    run_id: str
    split: str
    campaign_kind: str
    repetitions: int
    jwt_issuer: str
    jwt_audience: str
    jwt_secret: bytes
    settle_timeout_seconds: float
    poll_interval_seconds: float


@dataclass(frozen=True)
class RunRows:
    writes: list[dict[str, Any]]
    retrieval: list[dict[str, Any]]
    answers: list[dict[str, Any]]
    timings: list[dict[str, Any]]


@dataclass(frozen=True)
class PreparedBaseline:
    state: SummaryState | VectorState | None
    write_row: dict[str, Any]
    error_class: str | None = None


@dataclass(frozen=True)
class ExecutedContext:
    rendered: str
    ranked_event_ids: list[str]
    selected_event_ids: list[str]
    raw_selected_ids: list[str]
    citation_map: dict[str, str]
    context_tokens: int
    usage: ProviderUsage
    usage_complete: bool
    latency_ms: float
    diagnostics: dict[str, Any]


class UnifiedBenchmarkRunner:
    def __init__(
        self,
        *,
        runtime: BenchmarkRuntime,
        memos: BenchmarkMemosClient,
        dataset_manifest: dict[str, Any],
        scenarios: list[dict[str, Any]],
        answer_prompt: str,
        answer_schema: dict[str, Any],
        summary_prompt: str,
        summary_schema: dict[str, Any],
        settings: RunnerSettings,
        monotonic_ns: Any = time.perf_counter_ns,
        now_seconds: Any = time.time,
    ) -> None:
        self.runtime = runtime
        self.memos = memos
        self.manifest = dataset_manifest
        self.scenarios = scenarios
        self.answer_prompt = answer_prompt
        self.answer_schema = answer_schema
        self.summary_prompt = summary_prompt
        self.summary_schema = summary_schema
        self.settings = settings
        self.monotonic_ns = monotonic_ns
        self.now_seconds = now_seconds
        if set(dataset_manifest["baselines"]) != BASELINES:
            raise RunnerError("BASELINE_CONTRACT", "frozen baseline set is unsupported")
        _require_final_question_cutoffs(scenarios, settings.split)

    def execute(self) -> RunRows:
        writes: list[dict[str, Any]] = []
        retrieval: list[dict[str, Any]] = []
        answers: list[dict[str, Any]] = []
        timings: list[dict[str, Any]] = []
        selected = [
            scenario for scenario in self.scenarios if scenario["split"] == self.settings.split
        ]
        for repetition in range(1, self.settings.repetitions + 1):
            for scenario in selected:
                for baseline in self.manifest["baselines"]:
                    if baseline == "memos":
                        result = self._execute_memos_scenario(scenario, repetition)
                    else:
                        result = self._execute_local_scenario(baseline, scenario, repetition)
                    writes.append(result.writes[0])
                    retrieval.extend(result.retrieval)
                    answers.extend(result.answers)
                    timings.extend(result.timings)
        return RunRows(
            writes=sorted(writes, key=write_key),
            retrieval=sorted(retrieval, key=execution_key),
            answers=sorted(answers, key=execution_key),
            timings=sorted(timings, key=execution_key),
        )

    def _execute_local_scenario(
        self, baseline: str, scenario: dict[str, Any], repetition: int
    ) -> RunRows:
        prepared = self._prepare_local(baseline, scenario, repetition)
        retrieval: list[dict[str, Any]] = []
        answers: list[dict[str, Any]] = []
        timings: list[dict[str, Any]] = []
        for question in scenario["questions"]:
            identity = _identity(baseline, scenario, question, repetition)
            if prepared.error_class is not None:
                failed = _failed_execution(identity, prepared.error_class, 0.0)
                retrieval.append(failed[0])
                answers.append(failed[1])
                timings.append(failed[2])
                continue
            started = self.monotonic_ns()
            try:
                context = self._local_context(baseline, scenario, question, prepared.state)
            except (BaselineError, OllamaError, ValueError) as exc:
                latency = _elapsed_ms(started, self.monotonic_ns())
                failed = _failed_execution(identity, _error_class("RETRIEVAL", exc), latency, False)
                retrieval.append(failed[0])
                answers.append(failed[1])
                timings.append(failed[2])
                continue
            rows = self._answer_execution(identity, question, context)
            retrieval.append(rows[0])
            answers.append(rows[1])
            timings.append(rows[2])
        return RunRows([prepared.write_row], retrieval, answers, timings)

    def _prepare_local(
        self, baseline: str, scenario: dict[str, Any], repetition: int
    ) -> PreparedBaseline:
        base = {
            "baseline": baseline,
            "scenario_id": scenario["scenario_id"],
            "repetition": repetition,
        }
        if baseline == "full_history":
            return PreparedBaseline(
                None,
                base
                | {
                    "status": "SUCCESS",
                    "usage": ZERO_USAGE.as_dict(),
                    "usage_complete": True,
                    "latency_ms": 0.0,
                    "stage": "no_preprocessing",
                },
            )
        started = self.monotonic_ns()
        try:
            if baseline == "rolling_summary":
                state = prepare_rolling_summary(
                    scenario,
                    self.runtime,
                    self._model_tag("summary"),
                    self.summary_prompt,
                    self.summary_schema,
                    self.manifest["sampling"]["temperature"],
                    self.manifest["sampling"]["seed"],
                    repetition,
                )
                usage = _sum_usage(row["usage"] for row in state.write_rows)
                provider_ms = round(sum(row["latency_ms"] for row in state.write_rows), 3)
                details = {"summary_updates": len(state.write_rows)}
            elif baseline == "raw_turn_vector":
                state = prepare_raw_vector(
                    scenario, self.runtime, self._model_tag("embedding"), repetition
                )
                usage = _usage_from_dict(state.write_row["usage"])
                provider_ms = float(state.write_row["latency_ms"])
                details = {"embedded_events": len(state.events)}
            else:
                raise RunnerError("BASELINE_CONTRACT", "unsupported local baseline")
        except (BaselineError, OllamaError, ValueError) as exc:
            error_class = _error_class("WRITE", exc)
            return PreparedBaseline(
                None,
                base
                | {
                    "status": "FAILED",
                    "error_class": error_class,
                    "usage": ZERO_USAGE.as_dict(),
                    "usage_complete": False,
                    "latency_ms": _elapsed_ms(started, self.monotonic_ns()),
                },
                error_class,
            )
        return PreparedBaseline(
            state,
            base
            | {
                "status": "SUCCESS",
                "usage": usage.as_dict(),
                "usage_complete": True,
                "latency_ms": _elapsed_ms(started, self.monotonic_ns()),
                "provider_latency_ms": provider_ms,
                **details,
            },
        )

    def _local_context(
        self,
        baseline: str,
        scenario: dict[str, Any],
        question: dict[str, Any],
        state: SummaryState | VectorState | None,
    ) -> ExecutedContext:
        started = self.monotonic_ns()
        limits = self.manifest["limits"]
        embedding = self._model_tag("embedding")
        if baseline == "full_history":
            result = full_history_context(
                scenario,
                question["after_event_id"],
                self.runtime,
                embedding,
                limits["evidence_tokens"],
            )
        elif baseline == "rolling_summary" and isinstance(state, SummaryState):
            result = rolling_summary_context(
                state,
                question["after_event_id"],
                self.runtime,
                embedding,
                limits["summary_recent_turns"],
                limits["evidence_tokens"],
            )
        elif baseline == "raw_turn_vector" and isinstance(state, VectorState):
            result = raw_vector_context(
                state,
                question["query"],
                question["after_event_id"],
                self.runtime,
                embedding,
                limits["vector_top_k"],
                limits["evidence_tokens"],
            )
        else:
            raise RunnerError("BASELINE_STATE", "baseline preprocessing state is unavailable")
        return _local_executed_context(result, _elapsed_ms(started, self.monotonic_ns()))

    def _execute_memos_scenario(self, scenario: dict[str, Any], repetition: int) -> RunRows:
        baseline = "memos"
        suffix = _scope_suffix(self.settings.run_id, scenario["scenario_id"], repetition)
        scope = {
            name: _scoped_identifier(value, suffix) for name, value in scenario["scope"].items()
        }
        token = create_hs256_token(
            tenant=scope["tenant_id"],
            user=scope["user_id"],
            agent=scope["agent_id"],
            subject=f"benchmark-{suffix}",
            roles=["USER", "OPERATOR"],
            issuer=self.settings.jwt_issuer,
            audience=self.settings.jwt_audience,
            secret=self.settings.jwt_secret,
            now=int(self.now_seconds()),
            lifetime_seconds=max(
                3_600,
                int(
                    self.settings.settle_timeout_seconds
                    * sum(len(session["events"]) for session in scenario["sessions"])
                    * 2
                ),
            ),
        )
        source_map: dict[str, str] = {}
        write_usage = ZERO_USAGE
        usage_complete = True
        ingestion_ms = 0.0
        settlement_ms = 0.0
        freshness_samples: list[float] = []
        started = self.monotonic_ns()
        error_class: str | None = None
        try:
            for session in scenario["sessions"]:
                for event in session["events"]:
                    source_id = f"{event['event_id']}.{suffix}"
                    request = {
                        "sourceId": source_id,
                        "sessionId": f"{session['session_id']}.{suffix}",
                        "actorType": event["actor_type"],
                        "sourceType": event["source_type"],
                        "trustLevel": event["trust_level"],
                        "occurredAt": event["occurred_at"],
                        "payload": {"content": event["content"]},
                    }
                    ingest_started = self.monotonic_ns()
                    receipt = self.memos.ingest_source_event(
                        request,
                        token,
                        f"benchmark:{suffix}:{event['event_id']}",
                    )
                    ingestion_ms += _elapsed_ms(ingest_started, self.monotonic_ns())
                    if receipt.source_id != source_id or receipt.disposition != "ACCEPTED":
                        raise RunnerError("SCOPE_CONTAMINATION", "source was not freshly accepted")
                    settle_started = self.monotonic_ns()
                    status = self.memos.wait_for_materialization(
                        receipt.source_event_id,
                        token,
                        settle_timeout_seconds=self.settings.settle_timeout_seconds,
                        poll_interval_seconds=self.settings.poll_interval_seconds,
                    )
                    settlement_ms += _elapsed_ms(settle_started, self.monotonic_ns())
                    if not status.usage.complete:
                        raise RunnerError(
                            "MATERIALIZATION_USAGE_INCOMPLETE",
                            "source materialization usage is not exact",
                        )
                    write_usage += _materialization_usage(status)
                    usage_complete = usage_complete and status.usage.complete
                    freshness_samples.append(_freshness_ms(status))
                    if receipt.source_event_id in source_map:
                        raise RunnerError("SOURCE_IDENTITY", "source UUID was reused")
                    source_map[receipt.source_event_id] = event["event_id"]
        except (MemosClientError, RunnerError, ValueError) as exc:
            error_class = _error_class("WRITE", exc)
            usage_complete = False

        write_row = {
            "baseline": baseline,
            "scenario_id": scenario["scenario_id"],
            "repetition": repetition,
            "status": "FAILED" if error_class else "SUCCESS",
            "usage": write_usage.as_dict(),
            "usage_complete": usage_complete,
            "latency_ms": _elapsed_ms(started, self.monotonic_ns()),
            "ingestion_ms": round(ingestion_ms, 3),
            "settlement_wait_ms": round(settlement_ms, 3),
            "freshness_ms": freshness_samples,
        }
        if error_class:
            write_row["error_class"] = error_class

        retrieval: list[dict[str, Any]] = []
        answers: list[dict[str, Any]] = []
        timings: list[dict[str, Any]] = []
        for question in scenario["questions"]:
            identity = _identity(baseline, scenario, question, repetition)
            if error_class:
                failed = _failed_execution(identity, error_class, 0.0, usage_complete)
                retrieval.append(failed[0])
                answers.append(failed[1])
                timings.append(failed[2])
                continue
            retrieval_started = self.monotonic_ns()
            try:
                context = self._memos_context(question, token, source_map)
            except (MemosClientError, OllamaError, RunnerError, ValueError) as exc:
                latency = _elapsed_ms(retrieval_started, self.monotonic_ns())
                failed = _failed_execution(identity, _error_class("RETRIEVAL", exc), latency, False)
                retrieval.append(failed[0])
                answers.append(failed[1])
                timings.append(failed[2])
                continue
            rows = self._answer_execution(identity, question, context)
            retrieval.append(rows[0])
            answers.append(rows[1])
            timings.append(rows[2])
        return RunRows([write_row], retrieval, answers, timings)

    def _memos_context(
        self, question: dict[str, Any], token: str, source_map: dict[str, str]
    ) -> ExecutedContext:
        started = self.monotonic_ns()
        response = self.memos.retrieval_trace(
            question["query"],
            token,
            limit=self.manifest["limits"]["retrieval_limit"],
            max_tokens=self.manifest["limits"]["evidence_tokens"],
        )
        expected_version = (
            "sha256:" + self.manifest["selected_models"]["embedding"]["ollama_model_id"]
        )
        if response.context.token_counter_version != expected_version:
            raise RunnerError("TOKENIZER_IDENTITY", "MemOS context tokenizer identity differs")
        query_calls = 0
        if response.trace.embedding_provider != "not-called":
            if response.trace.embedding_model_version != expected_version:
                raise RunnerError("EMBEDDING_IDENTITY", "MemOS query embedding identity differs")
            query_calls = 1
        elif response.trace.embedding_model_version != "not-called":
            raise RunnerError("EMBEDDING_IDENTITY", "MemOS gated embedding trace is inconsistent")
        if response.context.tokens > self.manifest["limits"]["evidence_tokens"]:
            raise RunnerError("CONTEXT_BUDGET", "MemOS context exceeds the shared token budget")
        verification = self.runtime.embed(
            model=self._model_tag("embedding"), inputs=[response.context.rendered]
        )
        if len(verification.vectors) != 1:
            raise RunnerError("TOKENIZER_PARITY", "tokenizer verification returned wrong count")
        if verification.usage.embedding_tokens != response.context.tokens:
            raise RunnerError("TOKENIZER_PARITY", "Java and runner context token counts differ")
        ranked = _map_source_ids(response.ranked_source_event_ids, source_map)
        selected = _map_source_ids(response.selected_source_event_ids, source_map)
        usage = ProviderUsage(
            embedding_tokens=(
                response.trace.embedding_input_tokens
                + response.context.token_count_provider_input_tokens
            ),
            model_calls=query_calls + response.context.token_count_provider_calls,
        )
        return ExecutedContext(
            rendered=response.context.rendered,
            ranked_event_ids=ranked,
            selected_event_ids=selected,
            raw_selected_ids=list(response.selected_source_event_ids),
            citation_map={source_id: event_id for source_id, event_id in source_map.items()},
            context_tokens=response.context.tokens,
            usage=usage,
            usage_complete=True,
            latency_ms=_elapsed_ms(started, self.monotonic_ns()),
            diagnostics={
                "token_counter_version": response.context.token_counter_version,
                "tokenizer_verification_tokens": verification.usage.embedding_tokens,
                "tokenizer_verification_usage": verification.usage.as_dict(),
                "tokenizer_verification_latency_ms": verification.latency_ms,
            },
        )

    def _answer_execution(
        self,
        identity: dict[str, Any],
        question: dict[str, Any],
        context: ExecutedContext,
    ) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
        retrieval_row = identity | {
            "status": "SUCCESS",
            "ranked_event_ids": context.ranked_event_ids,
            "selected_event_ids": context.selected_event_ids,
            "context_tokens": context.context_tokens,
            "usage": context.usage.as_dict(),
            "usage_complete": context.usage_complete,
            **context.diagnostics,
        }
        answer_started = self.monotonic_ns()
        try:
            result = self.runtime.chat_json(
                model=self._model_tag("answer"),
                messages=[
                    {"role": "system", "content": self.answer_prompt},
                    {
                        "role": "user",
                        "content": canonical_json(
                            {"question": question["query"], "evidence": context.rendered}
                        ),
                    },
                ],
                schema=self.answer_schema,
                temperature=self.manifest["sampling"]["temperature"],
                seed=self.manifest["sampling"]["seed"],
            )
        except (OllamaError, ValueError) as exc:
            answer_ms = _elapsed_ms(answer_started, self.monotonic_ns())
            error_class = _error_class("ANSWER", exc)
            return (
                retrieval_row,
                identity
                | {
                    "status": "FAILED",
                    "error_class": error_class,
                    "usage": ZERO_USAGE.as_dict(),
                    "usage_complete": False,
                },
                _timing_row(
                    identity,
                    "FAILED",
                    context.latency_ms + answer_ms,
                    context.latency_ms,
                    answer_ms,
                    error_class,
                ),
            )
        answer_ms = _elapsed_ms(answer_started, self.monotonic_ns())
        try:
            output = validate_answer_output(
                result.value, context.raw_selected_ids, context.citation_map
            )
        except RunnerError as exc:
            error_class = _error_class("ANSWER", exc)
            return (
                retrieval_row,
                identity
                | {
                    "status": "FAILED",
                    "error_class": error_class,
                    "usage": result.usage.as_dict(),
                    "usage_complete": True,
                },
                _timing_row(
                    identity,
                    "FAILED",
                    context.latency_ms + answer_ms,
                    context.latency_ms,
                    answer_ms,
                    error_class,
                ),
            )
        return (
            retrieval_row,
            identity
            | {
                "status": "SUCCESS",
                "provided_evidence_ids": context.selected_event_ids,
                "output": output,
                "usage": result.usage.as_dict(),
                "usage_complete": True,
                "provider_total_ms": result.provider_total_ms,
                "provider_load_ms": result.provider_load_ms,
            },
            _timing_row(
                identity,
                "SUCCESS",
                context.latency_ms + answer_ms,
                context.latency_ms,
                answer_ms,
                None,
            ),
        )

    def _model_tag(self, role: str) -> str:
        model = self.manifest["selected_models"][role]
        if not isinstance(model, dict) or not isinstance(model.get("tag"), str):
            raise RunnerError("MODEL_CONTRACT", f"selected {role} model is unavailable")
        return model["tag"]


def validate_answer_output(
    value: dict[str, Any], allowed_citations: list[str], citation_map: dict[str, str]
) -> dict[str, Any]:
    fields = {"abstain", "answer", "items", "reason_code", "citations"}
    if not isinstance(value, dict) or set(value) != fields:
        raise RunnerError("ANSWER_SCHEMA", "answer output has an unexpected schema")
    if not isinstance(value["abstain"], bool):
        raise RunnerError("ANSWER_SCHEMA", "answer abstain value is invalid")
    if not isinstance(value["answer"], str) or len(value["answer"]) > 2048:
        raise RunnerError("ANSWER_SCHEMA", "answer scalar value is invalid")
    if not isinstance(value["reason_code"], str) or len(value["reason_code"]) > 128:
        raise RunnerError("ANSWER_SCHEMA", "answer reason code is invalid")
    items = value["items"]
    citations = value["citations"]
    if (
        not isinstance(items, list)
        or len(items) > 32
        or any(not isinstance(item, str) or not item or len(item) > 512 for item in items)
        or len(items) != len(set(items))
    ):
        raise RunnerError("ANSWER_SCHEMA", "answer items are invalid")
    if (
        not isinstance(citations, list)
        or len(citations) > 64
        or any(not isinstance(item, str) or not item or len(item) > 200 for item in citations)
        or len(citations) != len(set(citations))
        or not set(citations).issubset(allowed_citations)
    ):
        raise RunnerError("UNKNOWN_CITATION", "answer cites evidence outside the context")
    mapped = []
    for citation in citations:
        event_id = citation_map.get(citation)
        if event_id is None:
            raise RunnerError("UNKNOWN_CITATION", "answer citation cannot be mapped")
        if event_id not in mapped:
            mapped.append(event_id)
    return {**value, "items": list(items), "citations": mapped}


def create_hs256_token(
    *,
    tenant: str,
    user: str,
    agent: str,
    subject: str,
    roles: list[str],
    issuer: str,
    audience: str,
    secret: bytes,
    now: int,
    lifetime_seconds: int,
) -> str:
    if len(secret) < 32 or lifetime_seconds < 1:
        raise RunnerError("JWT_CONFIGURATION", "benchmark JWT configuration is invalid")
    header = _jwt_part({"alg": "HS256", "typ": "JWT"})
    payload = _jwt_part(
        {
            "agent_id": agent,
            "aud": [audience],
            "exp": now + lifetime_seconds,
            "iat": now,
            "iss": issuer,
            "roles": roles,
            "sub": subject,
            "tenant_id": tenant,
            "user_id": user,
        }
    )
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = base64.urlsafe_b64encode(
        hmac.new(secret, signing_input, hashlib.sha256).digest()
    ).rstrip(b"=")
    return f"{header}.{payload}.{signature.decode('ascii')}"


def run_command(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the frozen four-baseline MemOS benchmark")
    parser.add_argument(
        "--dataset-manifest",
        type=Path,
        default=Path("datasets/memos-assistant-smoke/v1/manifest.json"),
    )
    parser.add_argument("--output-root", type=Path, default=Path("../benchmark-artifacts"))
    parser.add_argument("--run-id")
    parser.add_argument("--split", default="dev", choices=("train", "dev", "test"))
    parser.add_argument("--campaign-kind", default="SMOKE", choices=("SMOKE", "FROZEN_TEST"))
    parser.add_argument("--memos-base-url", default="http://localhost:8080")
    parser.add_argument("--ollama-base-url", default="http://localhost:11434")
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--postgres-version", required=True)
    parser.add_argument("--pgvector-version", required=True)
    parser.add_argument("--settle-timeout-seconds", type=float, default=300.0)
    parser.add_argument("--poll-interval-seconds", type=float, default=0.25)
    args = parser.parse_args(argv)
    try:
        dataset_manifest, scenarios = load_dataset(args.dataset_manifest)
        repository, git_commit = _clean_repository()
        run_id = args.run_id or _default_run_id(git_commit)
        repetitions = dataset_manifest["sampling"][
            "smoke_repetitions" if args.campaign_kind == "SMOKE" else "formal_test_repetitions"
        ]
        runtime = OllamaClient(args.ollama_base_url)
        inventory = runtime.inspect(dataset_manifest["selected_models"])
        _validate_model_inventory(dataset_manifest, inventory)
        memos = MemosClient(args.memos_base_url)
        if memos.readiness().status != "UP":
            raise RunnerError("MEMOS_NOT_READY", "MemOS readiness is not UP")
        answer_prompt = _read_prompt(args.dataset_manifest, dataset_manifest, "answer")
        answer_schema = _read_json_prompt(args.dataset_manifest, dataset_manifest, "answer_schema")
        summary_prompt = _read_prompt(args.dataset_manifest, dataset_manifest, "summary")
        summary_schema = _read_json_prompt(
            args.dataset_manifest, dataset_manifest, "summary_schema"
        )
        settings = RunnerSettings(
            run_id=run_id,
            split=args.split,
            campaign_kind=args.campaign_kind,
            repetitions=repetitions,
            jwt_issuer=os.environ.get("MEMOS_JWT_ISSUER", "memos-local"),
            jwt_audience=os.environ.get("MEMOS_JWT_AUDIENCE", "memos-api"),
            jwt_secret=os.environ.get(
                "MEMOS_JWT_HMAC_SECRET", "memos-local-development-secret-change-me-now"
            ).encode("utf-8"),
            settle_timeout_seconds=args.settle_timeout_seconds,
            poll_interval_seconds=args.poll_interval_seconds,
        )
        runner = UnifiedBenchmarkRunner(
            runtime=runtime,
            memos=memos,
            dataset_manifest=dataset_manifest,
            scenarios=scenarios,
            answer_prompt=answer_prompt,
            answer_schema=answer_schema,
            summary_prompt=summary_prompt,
            summary_schema=summary_schema,
            settings=settings,
        )
        started_at = datetime.now(UTC).isoformat().replace("+00:00", "Z")
        run_manifest = build_run_manifest(
            run_id=run_id,
            started_at=started_at,
            git_commit=git_commit,
            dirty_worktree=False,
            dataset_manifest_path=args.dataset_manifest,
            split=args.split,
            campaign_kind=args.campaign_kind,
            repetitions=repetitions,
            models=dataset_manifest["selected_models"],
            environment={
                "python": platform.python_version(),
                "java": args.java_version,
                "postgres": args.postgres_version,
                "pgvector": args.pgvector_version,
                "ollama": inventory["version"],
                "machine": platform.platform(),
            },
        )
        cases = expected_case_rows(dataset_manifest, scenarios, args.split, repetitions)
        rows = runner.execute()
        metrics = generate_metrics(
            dataset_manifest,
            scenarios,
            run_manifest,
            rows.answers,
            rows.retrieval,
            rows.timings,
        )
        costs = generate_costs(run_manifest, rows.writes, rows.retrieval, rows.answers)
        run_dir = (args.output_root / run_id).resolve()
        write_package(
            run_dir,
            manifest=run_manifest,
            cases=cases,
            writes=rows.writes,
            retrieval=rows.retrieval,
            answers=rows.answers,
            timings=rows.timings,
            costs=costs,
            metrics=metrics,
        )
        summary = verify_package(run_dir, args.dataset_manifest)
    except (
        ArtifactPackageError,
        BaselineError,
        BenchmarkDatasetError,
        BenchmarkMetricError,
        MemosClientError,
        OllamaError,
        RunnerError,
        subprocess.CalledProcessError,
        OSError,
        ValueError,
    ) as exc:
        parser.error(f"{_error_class('RUN', exc)}")
    print(canonical_json({**summary, "run_dir": str(run_dir), "repository": str(repository)}))
    return 0


def _local_executed_context(result: ContextResult, latency_ms: float) -> ExecutedContext:
    selected = list(result.selected_event_ids)
    return ExecutedContext(
        rendered=result.rendered(),
        ranked_event_ids=list(result.ranked_event_ids),
        selected_event_ids=selected,
        raw_selected_ids=selected,
        citation_map={event_id: event_id for event_id in selected},
        context_tokens=result.context_tokens,
        usage=result.usage,
        usage_complete=True,
        latency_ms=latency_ms,
        diagnostics={"provider_context_latency_ms": result.latency_ms},
    )


def _failed_execution(
    identity: dict[str, Any], error_class: str, total_ms: float, usage_complete: bool = True
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    failed_usage = ZERO_USAGE.as_dict()
    return (
        identity
        | {
            "status": "FAILED",
            "error_class": error_class,
            "usage": failed_usage,
            "usage_complete": usage_complete,
        },
        identity
        | {
            "status": "FAILED",
            "error_class": error_class,
            "usage": failed_usage,
            "usage_complete": True,
        },
        _timing_row(identity, "FAILED", total_ms, total_ms, 0.0, error_class),
    )


def _timing_row(
    identity: dict[str, Any],
    status: str,
    total_ms: float,
    retrieval_ms: float,
    answer_ms: float,
    error_class: str | None,
) -> dict[str, Any]:
    row = identity | {
        "status": status,
        "total_ms": round(total_ms, 3),
        "stages_ms": {
            "retrieval": round(retrieval_ms, 3),
            "answer": round(answer_ms, 3),
        },
    }
    if error_class is not None:
        row["error_class"] = error_class
    return row


def _identity(
    baseline: str, scenario: dict[str, Any], question: dict[str, Any], repetition: int
) -> dict[str, Any]:
    return {
        "baseline": baseline,
        "scenario_id": scenario["scenario_id"],
        "question_id": question["question_id"],
        "repetition": repetition,
    }


def _sum_usage(values: Any) -> ProviderUsage:
    total = ZERO_USAGE
    for value in values:
        total += _usage_from_dict(value)
    return total


def _usage_from_dict(value: dict[str, Any]) -> ProviderUsage:
    return ProviderUsage(
        input_tokens=value["input_tokens"],
        output_tokens=value["output_tokens"],
        embedding_tokens=value["embedding_tokens"],
        model_calls=value["model_calls"],
    )


def _materialization_usage(status: SourceMaterializationStatus) -> ProviderUsage:
    return ProviderUsage(
        input_tokens=status.usage.input_tokens,
        output_tokens=status.usage.output_tokens,
        embedding_tokens=status.usage.embedding_tokens,
        model_calls=status.usage.model_calls,
    )


def _freshness_ms(status: SourceMaterializationStatus) -> float:
    if status.settled_at is None:
        raise RunnerError("MATERIALIZATION_STATE", "settled source lacks a timestamp")
    return round((status.settled_at - status.created_at).total_seconds() * 1000, 3)


def _map_source_ids(values: Any, source_map: dict[str, str]) -> list[str]:
    mapped: list[str] = []
    for source_id in values:
        event_id = source_map.get(source_id)
        if event_id is None:
            raise RunnerError("UNKNOWN_PROVENANCE", "MemOS returned unknown source provenance")
        if event_id not in mapped:
            mapped.append(event_id)
    return mapped


def _scope_suffix(run_id: str, scenario_id: str, repetition: int) -> str:
    return hashlib.sha256(f"{run_id}:{scenario_id}:{repetition}".encode()).hexdigest()[:16]


def _scoped_identifier(value: str, suffix: str) -> str:
    available = 128 - len(suffix) - 1
    return f"{value[:available]}.{suffix}"


def _jwt_part(value: dict[str, Any]) -> str:
    raw = json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _elapsed_ms(started_ns: int, finished_ns: int) -> float:
    return round((finished_ns - started_ns) / 1_000_000, 3)


def _error_class(stage: str, exc: BaseException) -> str:
    kind = getattr(exc, "kind", exc.__class__.__name__)
    safe = "".join(character if character.isalnum() else "_" for character in str(kind).upper())
    return f"{stage}_{safe[:96]}"


def _require_final_question_cutoffs(scenarios: list[dict[str, Any]], split: str) -> None:
    for scenario in scenarios:
        if scenario["split"] != split:
            continue
        final_event = scenario["sessions"][-1]["events"][-1]["event_id"]
        if any(question["after_event_id"] != final_event for question in scenario["questions"]):
            raise RunnerError(
                "FUTURE_LEAKAGE_GUARD",
                "v1 runner requires questions after the final scenario event",
            )


def _clean_repository() -> tuple[Path, str]:
    repository = Path(
        subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    )
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=normal"],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if status:
        raise RunnerError("DIRTY_WORKTREE", "benchmark execution requires a clean worktree")
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    return repository, commit


def _default_run_id(commit: str) -> str:
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    return f"{timestamp}-{commit[:12]}-smoke"


def _validate_model_inventory(manifest: dict[str, Any], inventory: dict[str, Any]) -> None:
    embedding_tag = manifest["selected_models"]["embedding"]["tag"]
    embedding = inventory["models"].get(embedding_tag)
    if not isinstance(embedding, dict) or embedding.get("embedding_length") != 1_024:
        raise RunnerError(
            "EMBEDDING_DIMENSIONS", "selected embedding model is not 1024-dimensional"
        )
    evidence_tokens = manifest["limits"]["evidence_tokens"]
    for role in ("summary", "answer"):
        tag = manifest["selected_models"][role]["tag"]
        model = inventory["models"].get(tag)
        context_length = None if not isinstance(model, dict) else model.get("context_length")
        if not isinstance(context_length, int) or context_length <= evidence_tokens:
            raise RunnerError("MODEL_CONTEXT", f"selected {role} model context is too small")


def _read_prompt(manifest_path: Path, manifest: dict[str, Any], name: str) -> str:
    path = (manifest_path.resolve().parent / manifest["prompts"][f"{name}_file"]).resolve()
    return path.read_text(encoding="utf-8")


def _read_json_prompt(manifest_path: Path, manifest: dict[str, Any], name: str) -> dict[str, Any]:
    value = json.loads(_read_prompt(manifest_path, manifest, name))
    if not isinstance(value, dict):
        raise RunnerError("PROMPT_SCHEMA", "prompt schema is not an object")
    return value


def main() -> int:
    return run_command()


if __name__ == "__main__":
    sys.exit(main())
