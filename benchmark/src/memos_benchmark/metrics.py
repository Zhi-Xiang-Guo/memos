"""Deterministic metrics derived from frozen labels and raw benchmark rows."""

from __future__ import annotations

import math
import unicodedata
from collections import Counter
from typing import Any

BASELINE_STATUS = {"SUCCESS", "FAILED", "EXCLUDED"}


class BenchmarkMetricError(ValueError):
    """Raised when raw rows cannot produce an auditable metric package."""


def execution_key(row: dict[str, Any]) -> tuple[str, str, str, int]:
    """Return the stable baseline/scenario/question/repetition identity for one row."""

    baseline = row.get("baseline")
    scenario_id = row.get("scenario_id")
    question_id = row.get("question_id")
    repetition = row.get("repetition")
    if (
        not isinstance(baseline, str)
        or not baseline
        or not isinstance(scenario_id, str)
        or not scenario_id
        or not isinstance(question_id, str)
        or not question_id
        or not isinstance(repetition, int)
        or isinstance(repetition, bool)
        or repetition < 1
    ):
        raise BenchmarkMetricError("row has an invalid execution identity")
    return baseline, scenario_id, question_id, repetition


def indexed_rows(
    rows: list[dict[str, Any]], name: str
) -> dict[tuple[str, str, str, int], dict[str, Any]]:
    indexed: dict[tuple[str, str, str, int], dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict):
            raise BenchmarkMetricError(f"{name} contains a non-object row")
        key = execution_key(row)
        if key in indexed:
            raise BenchmarkMetricError(f"{name} contains duplicate execution row {key}")
        indexed[key] = row
    return indexed


def _normalize(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold().strip()
    return " ".join(normalized.split())


def _normalized_values(values: list[str], field: str) -> list[str]:
    if any(not isinstance(value, str) or not value.strip() for value in values):
        raise BenchmarkMetricError(f"{field} must contain non-empty strings")
    return [_normalize(value) for value in values]


def _validated_output(row: dict[str, Any]) -> dict[str, Any]:
    output = row.get("output")
    if not isinstance(output, dict):
        raise BenchmarkMetricError("successful answer row requires an output object")
    required = {"abstain", "answer", "items", "reason_code", "citations"}
    if set(output) != required:
        raise BenchmarkMetricError("answer output has an unexpected schema")
    if not isinstance(output["abstain"], bool):
        raise BenchmarkMetricError("answer output abstain must be boolean")
    if not isinstance(output["answer"], str) or not isinstance(output["reason_code"], str):
        raise BenchmarkMetricError("answer output scalar fields must be strings")
    for field in ("items", "citations"):
        values = output[field]
        if (
            not isinstance(values, list)
            or any(not isinstance(value, str) or not value for value in values)
            or len(values) != len(set(values))
        ):
            raise BenchmarkMetricError(f"answer output {field} must contain unique strings")
    return output


def _answer_evaluation(question: dict[str, Any], row: dict[str, Any]) -> dict[str, bool]:
    output = _validated_output(row)
    answer = question["answer"]
    kind = answer["kind"]
    acceptable = set(_normalized_values(answer["acceptable"], "acceptable answers"))
    actual_answer = _normalize(output["answer"])
    actual_items = set(_normalized_values(output["items"], "answer items"))

    if kind == "ABSTAIN":
        correct = output["abstain"] and not actual_answer and not actual_items
    elif kind == "SET":
        correct = not output["abstain"] and not actual_answer and actual_items == acceptable
    else:
        correct = not output["abstain"] and not actual_items and actual_answer in acceptable

    rendered_values = [actual_answer, *actual_items]
    forbidden = _normalized_values(question["forbidden_answers"], "forbidden answers")
    forbidden_leakage = any(
        blocked and blocked in rendered
        for blocked in forbidden
        for rendered in rendered_values
        if rendered
    )
    provided = row.get("provided_evidence_ids")
    if (
        not isinstance(provided, list)
        or any(not isinstance(value, str) or not value for value in provided)
        or len(provided) != len(set(provided))
    ):
        raise BenchmarkMetricError("provided_evidence_ids must contain unique strings")
    citations = set(output["citations"])
    citation_valid = citations.issubset(set(provided))
    gold = set(question["gold_event_ids"])
    citation_complete = not gold or gold.issubset(citations)
    return {
        "correct": correct and not forbidden_leakage,
        "forbidden_leakage": forbidden_leakage,
        "citation_valid": citation_valid,
        "citation_complete": citation_complete,
        "predicted_abstain": output["abstain"],
    }


def _ratio(numerator: int | float, denominator: int) -> float | None:
    return round(numerator / denominator, 6) if denominator else None


def _f1(precision: float | None, recall: float | None) -> float | None:
    if precision is None or recall is None or precision + recall == 0:
        return None
    return round(2 * precision * recall / (precision + recall), 6)


def _nearest_rank(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return round(ordered[index], 3)


def _latency_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    values: list[float] = []
    for row in rows:
        value = row.get("total_ms")
        if not isinstance(value, (int, float)) or isinstance(value, bool) or value < 0:
            raise BenchmarkMetricError("timing total_ms must be non-negative")
        values.append(float(value))
    return {
        "samples": len(values),
        "p50": _nearest_rank(values, 0.50),
        "p95": _nearest_rank(values, 0.95),
        "p99": _nearest_rank(values, 0.99),
    }


def _abstention_metrics(expected: list[bool], predicted: list[bool]) -> dict[str, Any]:
    true_positive = sum(gold and actual for gold, actual in zip(expected, predicted, strict=True))
    false_positive = sum(
        not gold and actual for gold, actual in zip(expected, predicted, strict=True)
    )
    false_negative = sum(
        gold and not actual for gold, actual in zip(expected, predicted, strict=True)
    )
    true_negative = sum(
        not gold and not actual for gold, actual in zip(expected, predicted, strict=True)
    )
    precision = _ratio(true_positive, true_positive + false_positive)
    recall = _ratio(true_positive, true_positive + false_negative)
    return {
        "true_positive": true_positive,
        "false_positive": false_positive,
        "false_negative": false_negative,
        "true_negative": true_negative,
        "precision": precision,
        "recall": recall,
        "f1": _f1(precision, recall),
    }


def _retrieval_evaluation(question: dict[str, Any], row: dict[str, Any]) -> dict[str, Any] | None:
    gold = set(question["gold_event_ids"])
    if not gold:
        return None
    ranked = row.get("ranked_event_ids")
    selected = row.get("selected_event_ids")
    if (
        not isinstance(ranked, list)
        or any(not isinstance(value, str) or not value for value in ranked)
        or len(ranked) != len(set(ranked))
        or not isinstance(selected, list)
        or any(not isinstance(value, str) or not value for value in selected)
        or len(selected) != len(set(selected))
    ):
        raise BenchmarkMetricError("successful retrieval row has invalid evidence IDs")
    positions = [ranked.index(event_id) + 1 for event_id in gold if event_id in ranked]
    forbidden = set(question["forbidden_event_ids"])
    selected_set = set(selected)
    return {
        "hit": bool(gold & set(ranked)),
        "complete": gold.issubset(set(ranked)),
        "reciprocal_rank": 1 / min(positions) if positions else 0.0,
        "selected_complete": gold.issubset(selected_set),
        "selected_relevant": len(gold & selected_set),
        "selected_total": len(selected),
        "forbidden_labeled": bool(forbidden),
        "forbidden_exposure": bool(forbidden & selected_set),
    }


def generate_metrics(
    dataset_manifest: dict[str, Any],
    scenarios: list[dict[str, Any]],
    run_manifest: dict[str, Any],
    answer_rows: list[dict[str, Any]],
    retrieval_rows: list[dict[str, Any]],
    timing_rows: list[dict[str, Any]],
) -> dict[str, Any]:
    """Generate byte-stable metrics; failures and exclusions remain in denominators."""

    answers = indexed_rows(answer_rows, "answers")
    retrieval = indexed_rows(retrieval_rows, "retrieval")
    timings = indexed_rows(timing_rows, "timings")
    question_map = {
        (scenario["scenario_id"], question["question_id"]): question
        for scenario in scenarios
        for question in scenario["questions"]
    }
    track_map = {
        (scenario["scenario_id"], question["question_id"]): scenario["tracks"]
        for scenario in scenarios
        for question in scenario["questions"]
    }
    execution = run_manifest["execution"]
    baselines = execution["baselines"]
    split = execution["split"]
    repetitions = execution["repetitions"]
    split_scenarios = [scenario for scenario in scenarios if scenario["split"] == split]
    expected = {
        (baseline, scenario["scenario_id"], question["question_id"], repetition)
        for baseline in baselines
        for scenario in split_scenarios
        for question in scenario["questions"]
        for repetition in range(1, repetitions + 1)
    }
    for name, actual in (
        ("answers", set(answers)),
        ("retrieval", set(retrieval)),
        ("timings", set(timings)),
    ):
        if actual != expected:
            raise BenchmarkMetricError(
                f"{name} execution set mismatch: missing={len(expected - actual)} "
                f"unexpected={len(actual - expected)}"
            )

    by_baseline: dict[str, Any] = {}
    for baseline in baselines:
        keys = sorted(key for key in expected if key[0] == baseline)
        status_counts: Counter[str] = Counter()
        answer_evaluations: list[dict[str, bool]] = []
        retrieval_evaluations: list[dict[str, Any]] = []
        context_evaluations: list[dict[str, Any]] = []
        expected_abstention: list[bool] = []
        predicted_abstention: list[bool] = []
        baseline_timings: list[dict[str, Any]] = []
        track_expected: Counter[str] = Counter()
        track_correct: Counter[str] = Counter()
        for key in keys:
            answer_row = answers[key]
            status = answer_row.get("status")
            if status not in BASELINE_STATUS:
                raise BenchmarkMetricError(f"answer row {key} has an invalid status")
            status_counts[status] += 1
            baseline_timings.append(timings[key])
            question = question_map[(key[1], key[2])]
            question_tracks = track_map[(key[1], key[2])]
            track_expected.update(question_tracks)
            expected_abstention.append(bool(question["must_abstain"]))
            if status == "SUCCESS":
                evaluation = _answer_evaluation(question, answer_row)
                answer_evaluations.append(evaluation)
                predicted_abstention.append(evaluation["predicted_abstain"])
                if evaluation["correct"]:
                    track_correct.update(question_tracks)
            else:
                predicted_abstention.append(False)
            retrieval_row = retrieval[key]
            if retrieval_row.get("status") == "SUCCESS":
                evaluation = _retrieval_evaluation(question, retrieval_row)
                if evaluation is not None:
                    context_evaluations.append(evaluation)
                    if baseline in {"raw_turn_vector", "memos"}:
                        retrieval_evaluations.append(evaluation)
            elif question["gold_event_ids"]:
                failed_evaluation = {
                    "hit": False,
                    "complete": False,
                    "reciprocal_rank": 0.0,
                    "selected_complete": False,
                    "selected_relevant": 0,
                    "selected_total": 0,
                    "forbidden_labeled": bool(question["forbidden_event_ids"]),
                    "forbidden_exposure": False,
                }
                context_evaluations.append(failed_evaluation)
                if baseline in {"raw_turn_vector", "memos"}:
                    retrieval_evaluations.append(failed_evaluation)

        correct = sum(value["correct"] for value in answer_evaluations)
        forbidden = sum(value["forbidden_leakage"] for value in answer_evaluations)
        citation_valid = sum(value["citation_valid"] for value in answer_evaluations)
        citation_complete = sum(value["citation_complete"] for value in answer_evaluations)
        reciprocal_rank = sum(value["reciprocal_rank"] for value in retrieval_evaluations)
        selected_relevant = sum(value["selected_relevant"] for value in context_evaluations)
        selected_total = sum(value["selected_total"] for value in context_evaluations)
        forbidden_labeled = sum(value["forbidden_labeled"] for value in context_evaluations)
        forbidden_exposure = sum(value["forbidden_exposure"] for value in context_evaluations)
        by_baseline[baseline] = {
            "expected": len(keys),
            "status": {name: status_counts[name] for name in sorted(BASELINE_STATUS)},
            "answer": {
                "correct": correct,
                "accuracy": _ratio(correct, len(keys)),
                "forbidden_leakage_count": forbidden,
                "forbidden_leakage_rate": _ratio(forbidden, len(keys)),
                "citation_valid_rate": _ratio(citation_valid, len(answer_evaluations)),
                "citation_complete_rate": _ratio(citation_complete, len(answer_evaluations)),
            },
            "answer_by_track": {
                track: {
                    "expected": track_expected[track],
                    "correct": track_correct[track],
                    "accuracy": _ratio(track_correct[track], track_expected[track]),
                }
                for track in sorted(track_expected)
            },
            "abstention": _abstention_metrics(expected_abstention, predicted_abstention),
            "retrieval": {
                "eligible": len(retrieval_evaluations),
                "recall_at_k": _ratio(
                    sum(value["hit"] for value in retrieval_evaluations),
                    len(retrieval_evaluations),
                ),
                "complete_recall_at_k": _ratio(
                    sum(value["complete"] for value in retrieval_evaluations),
                    len(retrieval_evaluations),
                ),
                "selected_complete_rate": _ratio(
                    sum(value["selected_complete"] for value in retrieval_evaluations),
                    len(retrieval_evaluations),
                ),
                "mrr": _ratio(reciprocal_rank, len(retrieval_evaluations)),
            },
            "context": {
                "eligible": len(context_evaluations),
                "selected_complete_rate": _ratio(
                    sum(value["selected_complete"] for value in context_evaluations),
                    len(context_evaluations),
                ),
                "context_precision": _ratio(selected_relevant, selected_total),
                "forbidden_context_labeled": forbidden_labeled,
                "forbidden_context_exposure_rate": _ratio(forbidden_exposure, forbidden_labeled),
            },
            "total_latency_ms": _latency_metrics(baseline_timings),
        }

    return {
        "schema_version": "memos-benchmark-metrics.v1",
        "report_kind": run_manifest["campaign_kind"],
        "dataset_version": dataset_manifest["dataset_version"],
        "split": split,
        "repetitions": repetitions,
        "disclaimer": (
            "SMOKE validates harness mechanics only; it is not a formal quality or production "
            "performance claim."
            if run_manifest["campaign_kind"] == "SMOKE"
            else "Model-dependent benchmark result; interpret with its manifest and raw failures."
        ),
        "baselines": by_baseline,
    }
