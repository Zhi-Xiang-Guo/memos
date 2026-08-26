from __future__ import annotations

import json
from pathlib import Path

import pytest

from memos_benchmark.write_policy_report import (
    DISCLAIMER,
    REPORT_KIND,
    FixtureReportError,
    generate_report,
    load_fixture,
    main,
)

FIXTURE_DIR = Path(__file__).parents[1] / "fixtures" / "write-policy" / "v1"
MANIFEST = FIXTURE_DIR / "manifest.json"


def _perfect_predictions(cases: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        {
            "case_id": case["case_id"],
            "validation": case["expected"]["validation"],  # type: ignore[index]
            "candidate_keys": case["expected"]["candidate_keys"],  # type: ignore[index]
            "decisions": [
                {"ordinal": decision["ordinal"], "decision": decision["decision"]}
                for decision in case["expected"]["decisions"]  # type: ignore[index]
            ],
        }
        for case in cases
    ]


def _write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def test_fixture_manifest_is_integral_and_has_required_coverage() -> None:
    manifest, cases, _case_path = load_fixture(MANIFEST)

    assert manifest["fixture_version"] == "write-policy-v1"
    assert manifest["schema_version"] == "memory-candidate.v1"
    assert len(cases) == 17
    assert {label for case in cases for label in case["coverage"]} == set(
        manifest["required_coverage"]
    )
    assert {case["case_id"] for case in cases if case["split"] == "test"} == set(
        manifest["frozen_test_case_ids"]
    )


def test_perfect_observations_generate_conformance_metrics(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    prediction_path = tmp_path / "predictions.jsonl"
    _write_jsonl(prediction_path, _perfect_predictions(cases))

    report = generate_report(MANIFEST, prediction_path)

    assert report["report_kind"] == REPORT_KIND
    assert report["disclaimer"] == DISCLAIMER
    assert report["metrics"]["candidate"]["f1"] == 1.0
    assert report["metrics"]["remember"]["f1"] == 1.0
    assert report["metrics"]["decision"]["macro_f1"] == 1.0
    assert report["metrics"]["validation_accuracy"] == 1.0
    assert report["metrics"]["harmful_write_rate"] == 0.0
    manifest_document = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assert report["fixture"]["case_sha256"] == manifest_document["case_sha256"]


def test_harmful_remember_is_reported_without_changing_policy(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    predictions = _perfect_predictions(cases)
    secret = next(row for row in predictions if row["case_id"] == "credential-secret")
    secret["decisions"] = [{"ordinal": 0, "decision": "REMEMBER"}]
    prediction_path = tmp_path / "predictions.jsonl"
    _write_jsonl(prediction_path, predictions)

    report = generate_report(MANIFEST, prediction_path)

    assert report["metrics"]["harmful_write_count"] == 1
    assert report["metrics"]["harmful_write_rate"] > 0
    assert report["metrics"]["remember"]["precision"] < 1


def test_missing_prediction_case_is_a_hard_failure(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    prediction_path = tmp_path / "predictions.jsonl"
    _write_jsonl(prediction_path, _perfect_predictions(cases)[:-1])

    with pytest.raises(FixtureReportError, match="prediction case set mismatch"):
        generate_report(MANIFEST, prediction_path)


def test_cli_writes_machine_readable_report(tmp_path: Path) -> None:
    _manifest, cases, _case_path = load_fixture(MANIFEST)
    prediction_path = tmp_path / "predictions.jsonl"
    output_path = tmp_path / "report.json"
    _write_jsonl(prediction_path, _perfect_predictions(cases))

    assert (
        main(
            [
                "--manifest",
                str(MANIFEST),
                "--predictions",
                str(prediction_path),
                "--output",
                str(output_path),
            ]
        )
        == 0
    )
    report = json.loads(output_path.read_text(encoding="utf-8"))
    assert report["report_kind"] == "DETERMINISTIC_FIXTURE"
    assert "real-model benchmark" in report["disclaimer"]
