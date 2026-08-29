from __future__ import annotations

import json
from pathlib import Path

import pytest

from memos_benchmark.retrieval_conformance_report import (
    REPORT_KIND,
    RetrievalConformanceError,
    generate_report,
    load_fixture,
)

FIXTURE_DIR = Path(__file__).parents[1] / "fixtures" / "retrieval" / "v1"
MANIFEST = FIXTURE_DIR / "manifest.json"


def _perfect_predictions(cases: list[dict[str, object]]) -> list[dict[str, object]]:
    return [{"case_id": case["case_id"], "observed": case["expected"]} for case in cases]


def _write_jsonl(path: Path, values: list[dict[str, object]]) -> None:
    path.write_text("".join(json.dumps(value) + "\n" for value in values), encoding="utf-8")


def test_fixture_manifest_is_integral_and_frozen() -> None:
    manifest, cases, _ = load_fixture(MANIFEST)
    assert manifest["fixture_version"] == "retrieval-conformance-v1"
    assert len(cases) == 6
    assert manifest["splits"] == {"dev": 2, "test": 4}


def test_perfect_observations_generate_exact_metrics(tmp_path: Path) -> None:
    _manifest, cases, _ = load_fixture(MANIFEST)
    predictions = tmp_path / "predictions.jsonl"
    _write_jsonl(predictions, _perfect_predictions(cases))

    report = generate_report(MANIFEST, predictions)

    assert report["report_kind"] == REPORT_KIND
    assert report["summary"]["passed"] == 6
    assert report["summary"]["failed"] == 0
    assert report["summary"]["vector_only"] == {
        "eligible_case_count": 5,
        "recall_at_1": 0.6,
        "mrr": 0.6,
    }
    assert report["summary"]["hybrid"] == {
        "eligible_case_count": 5,
        "recall_at_1": 1.0,
        "mrr": 1.0,
    }


def test_missing_duplicate_and_mismatched_predictions_fail_closed(tmp_path: Path) -> None:
    _manifest, cases, _ = load_fixture(MANIFEST)
    perfect = _perfect_predictions(cases)

    missing = tmp_path / "missing.jsonl"
    _write_jsonl(missing, perfect[:-1])
    with pytest.raises(RetrievalConformanceError, match="case set"):
        generate_report(MANIFEST, missing)

    duplicate = tmp_path / "duplicate.jsonl"
    _write_jsonl(duplicate, perfect + [perfect[0]])
    with pytest.raises(RetrievalConformanceError, match="duplicate"):
        generate_report(MANIFEST, duplicate)

    mismatched = tmp_path / "mismatched.jsonl"
    values = _perfect_predictions(cases)
    values[0]["observed"] = {**values[0]["observed"], "hybrid_top_ids": []}
    _write_jsonl(mismatched, values)
    report = generate_report(MANIFEST, mismatched)
    assert report["summary"]["failed"] == 1
    assert report["cases"][0]["mismatch_paths"] == ["$.hybrid_top_ids[0]"]
