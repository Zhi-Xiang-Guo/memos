"""Validate Feature 4 observations and derive synthetic retrieval metrics."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

REPORT_KIND = "DETERMINISTIC_CONFORMANCE"
DISCLAIMER = (
    "Synthetic retrieval-policy conformance only; these fixture Recall@1 and MRR values are not "
    "real-model or production-system benchmark results and do not establish quality, latency, "
    "scale, cost, or safety."
)


class RetrievalConformanceError(ValueError):
    """Raised when fixture, predictions, or report inputs are inconsistent."""


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RetrievalConformanceError(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise RetrievalConformanceError(f"expected a JSON object in {path}")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _text_sha256(path: Path) -> str:
    normalized = path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def load_fixture(manifest_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]], Path]:
    """Load and integrity-check the versioned retrieval fixture."""

    manifest = _read_json(manifest_path)
    required = {
        "fixture_version",
        "retrieval_contract_version",
        "fusion_policy_version",
        "context_policy_version",
        "report_schema_version",
        "case_file",
        "case_sha256",
        "case_count",
        "splits",
        "required_coverage",
        "frozen_test_case_ids",
    }
    missing = sorted(required - manifest.keys())
    if missing:
        raise RetrievalConformanceError(f"manifest missing fields: {missing}")
    case_path = manifest_path.parent / str(manifest["case_file"])
    if _text_sha256(case_path) != manifest["case_sha256"]:
        raise RetrievalConformanceError("case SHA-256 mismatch")
    document = _read_json(case_path)
    if document.get("fixture_version") != manifest["fixture_version"]:
        raise RetrievalConformanceError("fixture version differs between manifest and case file")
    cases = document.get("cases")
    if not isinstance(cases, list) or len(cases) != manifest["case_count"]:
        raise RetrievalConformanceError("manifest case_count does not match the case file")
    case_ids = [case.get("case_id") for case in cases if isinstance(case, dict)]
    if len(case_ids) != len(cases) or len(case_ids) != len(set(case_ids)):
        raise RetrievalConformanceError("fixture case IDs must be present and unique")
    if dict(Counter(case.get("split") for case in cases)) != manifest["splits"]:
        raise RetrievalConformanceError("manifest split counts do not match the case file")
    coverage = {label for case in cases for label in case.get("coverage", [])}
    if coverage != set(manifest["required_coverage"]):
        raise RetrievalConformanceError("fixture coverage differs from the manifest")
    frozen = {case["case_id"] for case in cases if case.get("split") == "test"}
    if frozen != set(manifest["frozen_test_case_ids"]):
        raise RetrievalConformanceError("frozen test IDs differ from the test split")
    for case in cases:
        _validate_case(case)
    return manifest, cases, case_path


def _validate_case(case: dict[str, Any]) -> None:
    case_id = case["case_id"]
    if case.get("split") not in {"dev", "test"}:
        raise RetrievalConformanceError(f"case {case_id} has an invalid split")
    if not isinstance(case.get("query"), str) or not case["query"]:
        raise RetrievalConformanceError(f"case {case_id} requires a query")
    if not isinstance(case.get("relevant_ids"), list):
        raise RetrievalConformanceError(f"case {case_id} requires relevant_ids")
    expected = case.get("expected")
    expected_keys = {
        "gate_retrieve",
        "vector_top_ids",
        "hybrid_top_ids",
        "context_selected_ids",
    }
    if not isinstance(expected, dict) or set(expected) != expected_keys:
        raise RetrievalConformanceError(f"case {case_id} has an invalid expected contract")


def load_predictions(path: Path) -> list[dict[str, Any]]:
    """Load one observed prediction object per non-empty JSONL line."""

    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise RetrievalConformanceError(f"cannot read predictions {path}: {exc}") from exc
    predictions = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise RetrievalConformanceError(
                f"invalid prediction JSON on line {line_number}: {exc}"
            ) from exc
        if not isinstance(value, dict) or not isinstance(value.get("observed"), dict):
            raise RetrievalConformanceError(f"prediction line {line_number} is invalid")
        predictions.append(value)
    ids = [prediction.get("case_id") for prediction in predictions]
    if any(not isinstance(case_id, str) or not case_id for case_id in ids):
        raise RetrievalConformanceError("every prediction requires a case_id")
    if len(ids) != len(set(ids)):
        raise RetrievalConformanceError("duplicate prediction case_id")
    return predictions


def _mismatch_paths(expected: Any, observed: Any, path: str = "$") -> list[str]:
    if type(expected) is not type(observed):
        return [path]
    if isinstance(expected, dict):
        mismatches = []
        for key in sorted(set(expected) | set(observed)):
            if key not in expected or key not in observed:
                mismatches.append(f"{path}.{key}")
            else:
                mismatches.extend(_mismatch_paths(expected[key], observed[key], f"{path}.{key}"))
        return mismatches
    if isinstance(expected, list):
        mismatches = []
        for index in range(max(len(expected), len(observed))):
            if index >= len(expected) or index >= len(observed):
                mismatches.append(f"{path}[{index}]")
            else:
                mismatches.extend(
                    _mismatch_paths(expected[index], observed[index], f"{path}[{index}]")
                )
        return mismatches
    return [] if expected == observed else [path]


def _metrics(cases: list[dict[str, Any]], observed: dict[str, dict[str, Any]], key: str) -> dict:
    eligible = [case for case in cases if case["relevant_ids"]]
    recall_sum = 0.0
    reciprocal_rank_sum = 0.0
    for case in eligible:
        relevant = set(case["relevant_ids"])
        ranking = observed[case["case_id"]][key]
        recall_sum += len(set(ranking[:1]) & relevant) / len(relevant)
        ranks = [index for index, item in enumerate(ranking, start=1) if item in relevant]
        reciprocal_rank_sum += 0.0 if not ranks else 1.0 / min(ranks)
    count = len(eligible)
    return {
        "eligible_case_count": count,
        "recall_at_1": round(recall_sum / count, 6) if count else None,
        "mrr": round(reciprocal_rank_sum / count, 6) if count else None,
    }


def generate_report(manifest_path: Path, prediction_path: Path) -> dict[str, Any]:
    """Generate exact conformance and synthetic vector/hybrid metrics."""

    manifest, cases, case_path = load_fixture(manifest_path)
    predictions = load_predictions(prediction_path)
    by_id = {prediction["case_id"]: prediction["observed"] for prediction in predictions}
    expected_ids = {case["case_id"] for case in cases}
    if set(by_id) != expected_ids:
        raise RetrievalConformanceError("prediction case set differs from the fixture")
    results = []
    for case in cases:
        mismatches = _mismatch_paths(case["expected"], by_id[case["case_id"]])
        results.append(
            {
                "case_id": case["case_id"],
                "split": case["split"],
                "passed": not mismatches,
                "mismatch_paths": mismatches,
            }
        )
    passed = sum(result["passed"] for result in results)
    return {
        "report_kind": REPORT_KIND,
        "disclaimer": DISCLAIMER,
        "fixture": {
            "fixture_version": manifest["fixture_version"],
            "report_schema_version": manifest["report_schema_version"],
            "manifest_sha256": _sha256(manifest_path),
            "case_sha256": _text_sha256(case_path),
            "prediction_sha256": _sha256(prediction_path),
            "case_count": len(cases),
            "splits": manifest["splits"],
        },
        "summary": {
            "case_count": len(cases),
            "passed": passed,
            "failed": len(cases) - passed,
            "vector_only": _metrics(cases, by_id, "vector_top_ids"),
            "hybrid": _metrics(cases, by_id, "hybrid_top_ids"),
        },
        "cases": results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate a deterministic Feature 4 retrieval conformance report"
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = generate_report(args.manifest, args.predictions)
    except RetrievalConformanceError as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
