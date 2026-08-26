from __future__ import annotations

import json
from pathlib import Path

import pytest

from memos_benchmark.temporal_conformance_report import (
    DISCLAIMER,
    REPORT_KIND,
    TemporalConformanceError,
    generate_report,
    load_fixture,
    main,
)

FIXTURE_DIR = Path(__file__).parents[1] / "fixtures" / "temporal-memory" / "v1"
MANIFEST = FIXTURE_DIR / "manifest.json"


def _perfect_predictions(cases: list[dict[str, object]]) -> list[dict[str, object]]:
    return [{"case_id": case["case_id"], "observed": case["expected"]} for case in cases]


def _write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def test_manifest_is_integral_and_covers_feature_three_contract() -> None:
    manifest, cases, _case_path = load_fixture(MANIFEST)

    assert manifest["fixture_version"] == "temporal-memory-v1"
    assert manifest["contract_version"] == "temporal-memory.v1"
    assert len(cases) == 14
    assert {label for case in cases for label in case["coverage"]} == set(
        manifest["required_coverage"]
    )
    assert {case["case_id"] for case in cases if case["split"] == "test"} == set(
        manifest["frozen_test_case_ids"]
    )


def test_perfect_observations_generate_exact_conformance(tmp_path: Path) -> None:
    manifest, cases, _case_path = load_fixture(MANIFEST)
    predictions = tmp_path / "predictions.jsonl"
    _write_jsonl(predictions, _perfect_predictions(cases))

    report = generate_report(MANIFEST, predictions)

    assert report["report_kind"] == REPORT_KIND
    assert report["disclaimer"] == DISCLAIMER
    assert report["summary"] == {
        "case_count": 14,
        "passed": 14,
        "failed": 0,
        "exact_conformance_rate": 1.0,
    }
    assert all(result["mismatch_paths"] == [] for result in report["cases"])
    assert report["fixture"]["case_sha256"] == manifest["case_sha256"]


def test_mismatch_reports_paths_without_echoing_values(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    predictions = _perfect_predictions(cases)
    residence = next(
        prediction
        for prediction in predictions
        if prediction["case_id"] == "residence-shanghai-to-hangzhou"
    )
    residence["observed"] = dict(residence["observed"])  # type: ignore[arg-type]
    residence["observed"]["current_values"] = ["unexpected-sensitive-value"]  # type: ignore[index]
    prediction_path = tmp_path / "predictions.jsonl"
    _write_jsonl(prediction_path, predictions)

    report = generate_report(MANIFEST, prediction_path)
    failed_case = next(result for result in report["cases"] if not result["passed"])

    assert report["summary"]["failed"] == 1
    assert failed_case["mismatch_paths"] == ["$.current_values[0]"]
    assert "unexpected-sensitive-value" not in json.dumps(report)


def test_missing_and_duplicate_predictions_fail_closed(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    predictions = _perfect_predictions(cases)
    missing_path = tmp_path / "missing.jsonl"
    _write_jsonl(missing_path, predictions[:-1])
    with pytest.raises(TemporalConformanceError, match="prediction case set mismatch"):
        generate_report(MANIFEST, missing_path)

    duplicate_path = tmp_path / "duplicate.jsonl"
    _write_jsonl(duplicate_path, predictions + [predictions[0]])
    with pytest.raises(TemporalConformanceError, match="duplicate prediction case_id"):
        generate_report(MANIFEST, duplicate_path)


def test_manifest_checksum_mismatch_fails_closed(tmp_path: Path) -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["case_file"] = "cases.json"
    fixture_dir = tmp_path / "fixture"
    fixture_dir.mkdir()
    (fixture_dir / "cases.json").write_text(
        (FIXTURE_DIR / "cases.json").read_text(encoding="utf-8") + "\n",
        encoding="utf-8",
    )
    bad_manifest = fixture_dir / "manifest.json"
    bad_manifest.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(TemporalConformanceError, match="case SHA-256 mismatch"):
        load_fixture(bad_manifest)


def test_cli_writes_machine_readable_report(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    predictions = tmp_path / "predictions.jsonl"
    output = tmp_path / "report.json"
    _write_jsonl(predictions, _perfect_predictions(cases))

    assert (
        main(
            [
                "--manifest",
                str(MANIFEST),
                "--predictions",
                str(predictions),
                "--output",
                str(output),
            ]
        )
        == 0
    )
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["report_kind"] == "DETERMINISTIC_CONFORMANCE"
    assert "not a model or system benchmark" in report["disclaimer"]
