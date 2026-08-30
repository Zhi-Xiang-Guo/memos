from __future__ import annotations

from copy import deepcopy

import pytest
from test_artifacts import _run_data

from memos_benchmark.metrics import BenchmarkMetricError, _abstention_metrics, generate_metrics


def test_abstention_f1_is_zero_for_missed_or_spurious_positive_predictions() -> None:
    missed = _abstention_metrics([True], [False])
    spurious = _abstention_metrics([False], [True])

    assert missed["precision"] is None
    assert missed["recall"] == 0.0
    assert missed["f1"] == 0.0
    assert spurious["precision"] == 0.0
    assert spurious["recall"] is None
    assert spurious["f1"] == 0.0


def test_abstention_f1_is_inapplicable_without_gold_or_predicted_positives() -> None:
    result = _abstention_metrics([False], [False])

    assert result["precision"] is None
    assert result["recall"] is None
    assert result["f1"] is None


def test_perfect_dev_rows_produce_deterministic_metrics() -> None:
    dataset_manifest, scenarios, manifest, _, answers, retrieval, timings = _run_data()

    metrics = generate_metrics(dataset_manifest, scenarios, manifest, answers, retrieval, timings)

    for baseline in dataset_manifest["baselines"]:
        result = metrics["baselines"][baseline]
        assert result["answer"]["accuracy"] == 1.0
        if baseline in {"raw_turn_vector", "memos"}:
            assert result["retrieval"]["recall_at_k"] == 1.0
            assert result["retrieval"]["mrr"] == 1.0
        else:
            assert result["retrieval"]["recall_at_k"] is None
            assert result["retrieval"]["mrr"] is None
        assert result["context"]["context_precision"] == 1.0
        assert result["context"]["forbidden_context_exposure_rate"] == 0.0
        assert result["status"] == {"EXCLUDED": 0, "FAILED": 0, "SUCCESS": 3}
        assert result["answer_by_track"]["temporal"]["accuracy"] == 1.0
    assert metrics["report_kind"] == "SMOKE"
    assert "not a formal quality" in metrics["disclaimer"]


def test_failures_and_forbidden_answers_remain_in_denominators() -> None:
    dataset_manifest, scenarios, manifest, _, original_answers, original_retrieval, timings = (
        _run_data()
    )
    answers = deepcopy(original_answers)
    retrieval = deepcopy(original_retrieval)
    theme = next(
        row
        for row in answers
        if row["baseline"] == "full_history" and row["question_id"] == "dev-theme-q1"
    )
    theme["output"]["answer"] = "dark"
    missing = next(
        row
        for row in answers
        if row["baseline"] == "full_history" and row["question_id"] == "dev-missing-q1"
    )
    missing.clear()
    missing.update(
        {
            "baseline": "full_history",
            "scenario_id": "dev-missing-music",
            "question_id": "dev-missing-q1",
            "repetition": 1,
            "status": "FAILED",
            "error_class": "MODEL_TIMEOUT",
        }
    )
    release_retrieval = next(
        row
        for row in retrieval
        if row["baseline"] == "full_history" and row["question_id"] == "dev-release-q1"
    )
    release_retrieval.clear()
    release_retrieval.update(
        {
            "baseline": "full_history",
            "scenario_id": "dev-release-deadline",
            "question_id": "dev-release-q1",
            "repetition": 1,
            "status": "FAILED",
            "error_class": "EMBEDDING_TIMEOUT",
        }
    )
    theme_retrieval = next(
        row
        for row in retrieval
        if row["baseline"] == "full_history" and row["question_id"] == "dev-theme-q1"
    )
    theme_retrieval["selected_event_ids"].append("dev-theme-e1")

    metrics = generate_metrics(dataset_manifest, scenarios, manifest, answers, retrieval, timings)
    result = metrics["baselines"]["full_history"]

    assert result["answer"]["accuracy"] == 0.333333
    assert result["answer"]["forbidden_leakage_rate"] == 0.333333
    assert result["abstention"]["false_negative"] == 1
    assert result["retrieval"]["eligible"] == 0
    assert result["retrieval"]["recall_at_k"] is None
    assert result["context"]["eligible"] == 2
    assert result["context"]["context_precision"] == 0.5
    assert result["context"]["forbidden_context_exposure_rate"] == 1.0


def test_missing_execution_row_cannot_be_silently_excluded() -> None:
    dataset_manifest, scenarios, manifest, _, answers, retrieval, timings = _run_data()
    answers.pop()

    with pytest.raises(BenchmarkMetricError, match="execution set mismatch"):
        generate_metrics(dataset_manifest, scenarios, manifest, answers, retrieval, timings)
