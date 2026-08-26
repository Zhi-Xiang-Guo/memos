"""Generate a deterministic write-policy fixture conformance report.

This module compares labels. It intentionally does not implement candidate extraction,
authorization, sensitivity classification, or MemOS write policy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

REPORT_KIND = "DETERMINISTIC_FIXTURE"
DISCLAIMER = (
    "Deterministic fixture conformance only; this is not a real-model benchmark and does not "
    "establish production quality, safety, latency, token use, or cost."
)
DECISIONS = ("REMEMBER", "IGNORE", "REVIEW")


class FixtureReportError(ValueError):
    """Raised when fixture or prediction integrity is invalid."""


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise FixtureReportError(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise FixtureReportError(f"expected a JSON object in {path}")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_fixture(manifest_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]], Path]:
    """Load and verify a versioned fixture manifest and its cases."""

    manifest = _read_json(manifest_path)
    required_manifest_fields = {
        "fixture_version",
        "schema_version",
        "policy_version",
        "case_file",
        "case_sha256",
        "case_count",
        "splits",
        "required_coverage",
        "frozen_test_case_ids",
    }
    missing_fields = sorted(required_manifest_fields - manifest.keys())
    if missing_fields:
        raise FixtureReportError(f"manifest missing fields: {missing_fields}")

    case_path = manifest_path.parent / str(manifest["case_file"])
    actual_sha = _sha256(case_path)
    if actual_sha != manifest["case_sha256"]:
        raise FixtureReportError(
            f"case SHA-256 mismatch: manifest={manifest['case_sha256']} actual={actual_sha}"
        )

    case_document = _read_json(case_path)
    if case_document.get("fixture_version") != manifest["fixture_version"]:
        raise FixtureReportError("fixture version differs between manifest and case file")
    cases = case_document.get("cases")
    if not isinstance(cases, list):
        raise FixtureReportError("cases must be an array")
    if len(cases) != manifest["case_count"]:
        raise FixtureReportError("manifest case_count does not match the case file")

    case_ids = [case.get("case_id") for case in cases]
    if any(not isinstance(case_id, str) or not case_id for case_id in case_ids):
        raise FixtureReportError("every case requires a non-empty case_id")
    if len(set(case_ids)) != len(case_ids):
        raise FixtureReportError("duplicate fixture case_id")

    split_counts = Counter(case.get("split") for case in cases)
    if dict(sorted(split_counts.items())) != dict(sorted(manifest["splits"].items())):
        raise FixtureReportError("manifest split counts do not match the case file")

    coverage = {label for case in cases for label in case.get("coverage", [])}
    required_coverage = set(manifest["required_coverage"])
    if not required_coverage.issubset(coverage):
        raise FixtureReportError(
            f"missing required coverage labels: {sorted(required_coverage - coverage)}"
        )

    frozen_ids = set(manifest["frozen_test_case_ids"])
    actual_test_ids = {case["case_id"] for case in cases if case.get("split") == "test"}
    if frozen_ids != actual_test_ids:
        raise FixtureReportError("frozen_test_case_ids must equal the test split")

    for case in cases:
        _validate_fixture_case(case)
    return manifest, cases, case_path


def _validate_fixture_case(case: dict[str, Any]) -> None:
    expected = case.get("expected")
    provider_output = case.get("provider_output")
    if not isinstance(expected, dict) or not isinstance(provider_output, dict):
        raise FixtureReportError(f"case {case['case_id']} lacks expected/provider_output objects")
    if provider_output.get("schema_version") != "memory-candidate.v1":
        raise FixtureReportError(f"case {case['case_id']} has an unexpected schema_version")
    if expected.get("validation") not in {"VALID", "INVALID_SCHEMA"}:
        raise FixtureReportError(f"case {case['case_id']} has an invalid validation label")
    candidate_keys = expected.get("candidate_keys")
    decisions = expected.get("decisions")
    if not isinstance(candidate_keys, list) or len(candidate_keys) != len(set(candidate_keys)):
        raise FixtureReportError(f"case {case['case_id']} has invalid/duplicate candidate keys")
    if not isinstance(decisions, list):
        raise FixtureReportError(f"case {case['case_id']} decisions must be an array")
    ordinals = [decision.get("ordinal") for decision in decisions]
    if len(ordinals) != len(set(ordinals)):
        raise FixtureReportError(f"case {case['case_id']} has duplicate decision ordinals")
    if any(decision.get("decision") not in DECISIONS for decision in decisions):
        raise FixtureReportError(f"case {case['case_id']} has an invalid decision label")


def load_predictions(path: Path) -> list[dict[str, Any]]:
    """Load one prediction object per non-empty JSONL line."""

    predictions: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise FixtureReportError(f"cannot read predictions {path}: {exc}") from exc
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise FixtureReportError(
                f"invalid prediction JSON on line {line_number}: {exc}"
            ) from exc
        if not isinstance(value, dict):
            raise FixtureReportError(f"prediction line {line_number} is not an object")
        predictions.append(value)
    _validate_predictions(predictions)
    return predictions


def _validate_predictions(predictions: list[dict[str, Any]]) -> None:
    case_ids: list[str] = []
    for prediction in predictions:
        case_id = prediction.get("case_id")
        if not isinstance(case_id, str) or not case_id:
            raise FixtureReportError("every prediction requires a non-empty case_id")
        case_ids.append(case_id)
        if prediction.get("validation") not in {"VALID", "INVALID_SCHEMA"}:
            raise FixtureReportError(f"prediction {case_id} has an invalid validation label")
        candidate_keys = prediction.get("candidate_keys")
        decisions = prediction.get("decisions")
        if not isinstance(candidate_keys, list) or len(candidate_keys) != len(set(candidate_keys)):
            raise FixtureReportError(f"prediction {case_id} has invalid/duplicate candidate keys")
        if not isinstance(decisions, list):
            raise FixtureReportError(f"prediction {case_id} decisions must be an array")
        ordinals = [decision.get("ordinal") for decision in decisions]
        if len(ordinals) != len(set(ordinals)):
            raise FixtureReportError(f"prediction {case_id} has duplicate decision ordinals")
        if any(decision.get("decision") not in DECISIONS for decision in decisions):
            raise FixtureReportError(f"prediction {case_id} has an invalid decision label")
    if len(case_ids) != len(set(case_ids)):
        raise FixtureReportError("duplicate prediction case_id")


def _ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 6) if denominator else None


def _prf(expected_positive: set[str], predicted_positive: set[str]) -> dict[str, Any]:
    true_positive = len(expected_positive & predicted_positive)
    false_positive = len(predicted_positive - expected_positive)
    false_negative = len(expected_positive - predicted_positive)
    precision = _ratio(true_positive, true_positive + false_positive)
    recall = _ratio(true_positive, true_positive + false_negative)
    f1 = None
    if precision is not None and recall is not None and precision + recall:
        f1 = round(2 * precision * recall / (precision + recall), 6)
    return {
        "true_positive": true_positive,
        "false_positive": false_positive,
        "false_negative": false_negative,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def _decision_maps(
    cases: list[dict[str, Any]], predictions_by_id: dict[str, dict[str, Any]]
) -> tuple[dict[str, str], dict[str, str]]:
    expected: dict[str, str] = {}
    predicted: dict[str, str] = {}
    for case in cases:
        case_id = case["case_id"]
        for decision in case["expected"]["decisions"]:
            expected[f"{case_id}:{decision['ordinal']}"] = decision["decision"]
        for decision in predictions_by_id[case_id]["decisions"]:
            predicted[f"{case_id}:{decision['ordinal']}"] = decision["decision"]
    return expected, predicted


def _decision_metrics(expected: dict[str, str], predicted: dict[str, str]) -> dict[str, Any]:
    per_class: dict[str, dict[str, Any]] = {}
    for decision in DECISIONS:
        expected_positive = {token for token, label in expected.items() if label == decision}
        predicted_positive = {token for token, label in predicted.items() if label == decision}
        per_class[decision] = _prf(expected_positive, predicted_positive)
    f1_values = [metric["f1"] for metric in per_class.values() if metric["f1"] is not None]
    return {
        "per_class": per_class,
        "macro_f1": round(sum(f1_values) / len(f1_values), 6) if f1_values else None,
    }


def _candidate_metrics(
    cases: list[dict[str, Any]], predictions_by_id: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    expected = {
        f"{case['case_id']}:{key}" for case in cases for key in case["expected"]["candidate_keys"]
    }
    predicted = {
        f"{case['case_id']}:{key}"
        for case in cases
        for key in predictions_by_id[case["case_id"]]["candidate_keys"]
    }
    return _prf(expected, predicted)


def _group_tokens(cases: list[dict[str, Any]]) -> dict[str, dict[str, set[str]]]:
    grouped: dict[str, dict[str, set[str]]] = {
        "memory_type": defaultdict(set),
        "source_type": defaultdict(set),
        "sensitivity": defaultdict(set),
    }
    for case in cases:
        candidates = case["provider_output"].get("candidates", [])
        for decision in case["expected"]["decisions"]:
            ordinal = decision["ordinal"]
            if not isinstance(ordinal, int) or ordinal < 0 or ordinal >= len(candidates):
                raise FixtureReportError(f"case {case['case_id']} decision ordinal is out of range")
            token = f"{case['case_id']}:{ordinal}"
            candidate = candidates[ordinal]
            grouped["memory_type"][candidate["memory_type"]].add(token)
            grouped["source_type"][case["source"]["source_type"]].add(token)
            for sensitivity in candidate["sensitivity"]:
                grouped["sensitivity"][sensitivity].add(token)
    return grouped


def _grouped_remember_metrics(
    cases: list[dict[str, Any]], expected: dict[str, str], predicted: dict[str, str]
) -> dict[str, Any]:
    expected_remember = {token for token, label in expected.items() if label == "REMEMBER"}
    predicted_remember = {token for token, label in predicted.items() if label == "REMEMBER"}
    output: dict[str, Any] = {}
    for dimension, groups in _group_tokens(cases).items():
        output[dimension] = {
            group: _prf(expected_remember & tokens, predicted_remember & tokens)
            for group, tokens in sorted(groups.items())
        }
    return output


def generate_report(manifest_path: Path, prediction_path: Path) -> dict[str, Any]:
    """Compare predictions with fixture labels and return a JSON-serializable report."""

    manifest, cases, case_path = load_fixture(manifest_path)
    predictions = load_predictions(prediction_path)
    predictions_by_id = {prediction["case_id"]: prediction for prediction in predictions}
    expected_ids = {case["case_id"] for case in cases}
    predicted_ids = set(predictions_by_id)
    if expected_ids != predicted_ids:
        raise FixtureReportError(
            "prediction case set mismatch: "
            f"missing={sorted(expected_ids - predicted_ids)} "
            f"unexpected={sorted(predicted_ids - expected_ids)}"
        )

    expected_decisions, predicted_decisions = _decision_maps(cases, predictions_by_id)
    remember_metrics = _prf(
        {token for token, label in expected_decisions.items() if label == "REMEMBER"},
        {token for token, label in predicted_decisions.items() if label == "REMEMBER"},
    )
    harmful_tokens = {
        f"{case['case_id']}:{ordinal}"
        for case in cases
        for ordinal in case["expected"]["harmful_if_remembered_ordinals"]
    }
    harmful_writes = len(
        harmful_tokens
        & {token for token, label in predicted_decisions.items() if label == "REMEMBER"}
    )
    validation_correct = sum(
        case["expected"]["validation"] == predictions_by_id[case["case_id"]]["validation"]
        for case in cases
    )
    decision_counts = Counter(predicted_decisions.values())
    expected_decision_count = len(expected_decisions)

    return {
        "report_kind": REPORT_KIND,
        "disclaimer": DISCLAIMER,
        "fixture": {
            "fixture_version": manifest["fixture_version"],
            "schema_version": manifest["schema_version"],
            "policy_version": manifest["policy_version"],
            "manifest_sha256": _sha256(manifest_path),
            "case_sha256": _sha256(case_path),
            "prediction_sha256": _sha256(prediction_path),
            "case_count": len(cases),
            "splits": manifest["splits"],
        },
        "metrics": {
            "candidate": _candidate_metrics(cases, predictions_by_id),
            "remember": remember_metrics,
            "decision": _decision_metrics(expected_decisions, predicted_decisions),
            "grouped_remember": _grouped_remember_metrics(
                cases, expected_decisions, predicted_decisions
            ),
            "validation_accuracy": _ratio(validation_correct, len(cases)),
            "invalid_schema_rate": _ratio(
                sum(prediction["validation"] == "INVALID_SCHEMA" for prediction in predictions),
                len(cases),
            ),
            "decision_ratios": {
                decision: _ratio(decision_counts[decision], expected_decision_count)
                for decision in DECISIONS
            }
            | {
                "MISSING": _ratio(
                    len(set(expected_decisions) - set(predicted_decisions)),
                    expected_decision_count,
                )
            },
            "harmful_write_rate": _ratio(harmful_writes, len(harmful_tokens)),
            "harmful_write_count": harmful_writes,
            "harmful_case_count": len(harmful_tokens),
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate a deterministic Feature 2 write-policy fixture report"
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        report = generate_report(args.manifest, args.predictions)
    except FixtureReportError as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
