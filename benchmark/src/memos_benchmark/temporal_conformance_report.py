"""Compare Feature 3 temporal-memory observations with a frozen fixture.

This module validates and compares observations. It intentionally does not implement
temporal transitions, deduplication, concurrency, provenance, or projection rebuilds.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

REPORT_KIND = "DETERMINISTIC_CONFORMANCE"
DISCLAIMER = (
    "Deterministic temporal-memory conformance only; this is not a model or system benchmark "
    "and does not establish production quality, safety, latency, throughput, or cost."
)


class TemporalConformanceError(ValueError):
    """Raised when fixture or prediction integrity is invalid."""


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise TemporalConformanceError(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise TemporalConformanceError(f"expected a JSON object in {path}")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_fixture(manifest_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]], Path]:
    """Load and verify a versioned temporal conformance fixture."""

    manifest = _read_json(manifest_path)
    required_fields = {
        "fixture_version",
        "contract_version",
        "transition_policy_version",
        "report_schema_version",
        "case_file",
        "case_sha256",
        "case_count",
        "splits",
        "required_coverage",
        "frozen_test_case_ids",
    }
    missing = sorted(required_fields - manifest.keys())
    if missing:
        raise TemporalConformanceError(f"manifest missing fields: {missing}")

    case_path = manifest_path.parent / str(manifest["case_file"])
    actual_sha = _sha256(case_path)
    if actual_sha != manifest["case_sha256"]:
        raise TemporalConformanceError(
            f"case SHA-256 mismatch: manifest={manifest['case_sha256']} actual={actual_sha}"
        )

    document = _read_json(case_path)
    if document.get("fixture_version") != manifest["fixture_version"]:
        raise TemporalConformanceError("fixture version differs between manifest and case file")
    cases = document.get("cases")
    if not isinstance(cases, list):
        raise TemporalConformanceError("cases must be an array")
    if len(cases) != manifest["case_count"]:
        raise TemporalConformanceError("manifest case_count does not match the case file")

    case_ids = [case.get("case_id") for case in cases if isinstance(case, dict)]
    if len(case_ids) != len(cases) or any(
        not isinstance(case_id, str) or not case_id for case_id in case_ids
    ):
        raise TemporalConformanceError("every case requires a non-empty case_id")
    if len(case_ids) != len(set(case_ids)):
        raise TemporalConformanceError("duplicate fixture case_id")

    split_counts = Counter(case.get("split") for case in cases)
    if dict(sorted(split_counts.items())) != dict(sorted(manifest["splits"].items())):
        raise TemporalConformanceError("manifest split counts do not match the case file")

    coverage = {label for case in cases for label in case.get("coverage", [])}
    required_coverage = set(manifest["required_coverage"])
    if coverage != required_coverage:
        raise TemporalConformanceError(
            "fixture coverage differs from manifest: "
            f"missing={sorted(required_coverage - coverage)} "
            f"unexpected={sorted(coverage - required_coverage)}"
        )

    frozen_ids = set(manifest["frozen_test_case_ids"])
    test_ids = {case["case_id"] for case in cases if case.get("split") == "test"}
    if frozen_ids != test_ids:
        raise TemporalConformanceError("frozen_test_case_ids must equal the test split")

    for case in cases:
        _validate_case(case)
    return manifest, cases, case_path


def _validate_case(case: dict[str, Any]) -> None:
    case_id = case["case_id"]
    if case.get("split") not in {"dev", "test"}:
        raise TemporalConformanceError(f"case {case_id} has an invalid split")
    coverage = case.get("coverage")
    if (
        not isinstance(coverage, list)
        or not coverage
        or any(not isinstance(label, str) or not label for label in coverage)
        or len(coverage) != len(set(coverage))
    ):
        raise TemporalConformanceError(f"case {case_id} has invalid coverage")
    scenario = case.get("scenario")
    expected = case.get("expected")
    if not isinstance(scenario, dict) or not isinstance(expected, dict):
        raise TemporalConformanceError(f"case {case_id} requires scenario and expected objects")
    scope = scenario.get("scope")
    commands = scenario.get("commands")
    if not isinstance(scope, dict) or set(scope) != {"tenant_id", "user_id", "agent_id"}:
        raise TemporalConformanceError(f"case {case_id} requires an exact hard scope")
    if any(not isinstance(value, str) or not value for value in scope.values()):
        raise TemporalConformanceError(f"case {case_id} scope values must be non-empty strings")
    if not isinstance(commands, list) or not commands:
        raise TemporalConformanceError(f"case {case_id} requires commands")
    if not all(isinstance(command, dict) and command.get("operation") for command in commands):
        raise TemporalConformanceError(f"case {case_id} has an invalid command")
    outcomes = expected.get("command_outcomes")
    if not isinstance(outcomes, list) or len(outcomes) != len(commands):
        raise TemporalConformanceError(f"case {case_id} command_outcomes must align with commands")
    operations = expected.get("transition_operations")
    if not isinstance(operations, list) or len(operations) != len(commands):
        raise TemporalConformanceError(
            f"case {case_id} transition_operations must align with commands"
        )


def load_predictions(path: Path) -> list[dict[str, Any]]:
    """Load one prediction object per non-empty JSONL line."""

    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise TemporalConformanceError(f"cannot read predictions {path}: {exc}") from exc
    predictions: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise TemporalConformanceError(
                f"invalid prediction JSON on line {line_number}: {exc}"
            ) from exc
        if not isinstance(value, dict):
            raise TemporalConformanceError(f"prediction line {line_number} is not an object")
        case_id = value.get("case_id")
        if not isinstance(case_id, str) or not case_id:
            raise TemporalConformanceError("every prediction requires a non-empty case_id")
        if not isinstance(value.get("observed"), dict):
            raise TemporalConformanceError(f"prediction {case_id} requires an observed object")
        predictions.append(value)
    case_ids = [prediction["case_id"] for prediction in predictions]
    if len(case_ids) != len(set(case_ids)):
        raise TemporalConformanceError("duplicate prediction case_id")
    return predictions


def _mismatch_paths(expected: Any, observed: Any, path: str = "$") -> list[str]:
    if type(expected) is not type(observed):
        return [path]
    if isinstance(expected, dict):
        mismatches: list[str] = []
        for key in sorted(set(expected) | set(observed)):
            child_path = f"{path}.{key}"
            if key not in expected or key not in observed:
                mismatches.append(child_path)
            else:
                mismatches.extend(_mismatch_paths(expected[key], observed[key], child_path))
        return mismatches
    if isinstance(expected, list):
        mismatches = []
        for index in range(max(len(expected), len(observed))):
            child_path = f"{path}[{index}]"
            if index >= len(expected) or index >= len(observed):
                mismatches.append(child_path)
            else:
                mismatches.extend(_mismatch_paths(expected[index], observed[index], child_path))
        return mismatches
    return [] if expected == observed else [path]


def generate_report(manifest_path: Path, prediction_path: Path) -> dict[str, Any]:
    """Return an exact deterministic conformance report without domain inference."""

    manifest, cases, case_path = load_fixture(manifest_path)
    predictions = load_predictions(prediction_path)
    predictions_by_id = {prediction["case_id"]: prediction for prediction in predictions}
    expected_ids = {case["case_id"] for case in cases}
    observed_ids = set(predictions_by_id)
    if expected_ids != observed_ids:
        raise TemporalConformanceError(
            "prediction case set mismatch: "
            f"missing={sorted(expected_ids - observed_ids)} "
            f"unexpected={sorted(observed_ids - expected_ids)}"
        )

    results: list[dict[str, Any]] = []
    for case in cases:
        mismatches = _mismatch_paths(
            case["expected"], predictions_by_id[case["case_id"]]["observed"]
        )
        results.append(
            {
                "case_id": case["case_id"],
                "split": case["split"],
                "coverage": case["coverage"],
                "passed": not mismatches,
                "mismatch_paths": mismatches,
            }
        )

    coverage_summary: dict[str, dict[str, int]] = {}
    for label in manifest["required_coverage"]:
        matching = [result for result in results if label in result["coverage"]]
        passed = sum(result["passed"] for result in matching)
        coverage_summary[label] = {
            "case_count": len(matching),
            "passed": passed,
            "failed": len(matching) - passed,
        }

    passed = sum(result["passed"] for result in results)
    return {
        "report_kind": REPORT_KIND,
        "disclaimer": DISCLAIMER,
        "fixture": {
            "fixture_version": manifest["fixture_version"],
            "contract_version": manifest["contract_version"],
            "transition_policy_version": manifest["transition_policy_version"],
            "report_schema_version": manifest["report_schema_version"],
            "manifest_sha256": _sha256(manifest_path),
            "case_sha256": _sha256(case_path),
            "prediction_sha256": _sha256(prediction_path),
            "case_count": len(cases),
            "splits": manifest["splits"],
        },
        "summary": {
            "case_count": len(results),
            "passed": passed,
            "failed": len(results) - passed,
            "exact_conformance_rate": round(passed / len(results), 6) if results else None,
        },
        "coverage": coverage_summary,
        "cases": results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate a deterministic Feature 3 temporal-memory conformance report"
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = generate_report(args.manifest, args.predictions)
    except TemporalConformanceError as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
