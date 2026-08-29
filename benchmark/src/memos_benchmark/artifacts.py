"""Immutable run-package writer and integrity verifier for Feature 6."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

from memos_benchmark.dataset import load_dataset, text_sha256
from memos_benchmark.metrics import (
    BenchmarkMetricError,
    execution_key,
    generate_metrics,
    indexed_rows,
)

RUN_SCHEMA = "memos-benchmark-run.v1"
INTEGRITY_SCHEMA = "memos-benchmark-integrity.v1"
CAMPAIGN_KINDS = {"SMOKE", "FROZEN_TEST"}
RAW_FILES = {
    "cases.jsonl",
    "writes.jsonl",
    "retrieval.jsonl",
    "answers.jsonl",
    "timings.jsonl",
}
JSON_FILES = {"manifest.json", "costs.json", "metrics.json"}
PACKAGE_FILES = RAW_FILES | JSON_FILES | {"failures.md"}
ALL_FILES = PACKAGE_FILES | {"integrity.json"}
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
GIT_COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
RUN_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,128}")
ENVIRONMENT_FIELDS = {"python", "java", "postgres", "pgvector", "ollama", "machine"}


class ArtifactPackageError(ValueError):
    """Raised when a run package is incomplete, mutable, or inconsistent."""


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(64 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise ArtifactPackageError(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest()


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ArtifactPackageError(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ArtifactPackageError(f"expected JSON object in {path}")
    return value


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise ArtifactPackageError(f"cannot read JSONL {path}: {exc}") from exc
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ArtifactPackageError(f"invalid JSONL {path}:{line_number}: {exc}") from exc
        if not isinstance(value, dict):
            raise ArtifactPackageError(f"JSONL {path}:{line_number} is not an object")
        rows.append(value)
    return rows


def _write_json(path: Path, value: Any) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True))
        stream.write("\n")


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(canonical_json(row))
            stream.write("\n")


def expected_case_rows(
    dataset_manifest: dict[str, Any],
    scenarios: list[dict[str, Any]],
    split: str,
    repetitions: int,
) -> list[dict[str, Any]]:
    """Expand the frozen dataset into one execution identity per baseline and repetition."""

    if split not in {"train", "dev", "test"}:
        raise ArtifactPackageError("execution split must be train, dev, or test")
    if not isinstance(repetitions, int) or isinstance(repetitions, bool) or repetitions < 1:
        raise ArtifactPackageError("repetitions must be a positive integer")
    rows = [
        {
            "baseline": baseline,
            "scenario_id": scenario["scenario_id"],
            "question_id": question["question_id"],
            "repetition": repetition,
            "tracks": scenario["tracks"],
        }
        for baseline in dataset_manifest["baselines"]
        for scenario in scenarios
        if scenario["split"] == split
        for question in scenario["questions"]
        for repetition in range(1, repetitions + 1)
    ]
    return sorted(rows, key=execution_key)


def split_membership_sha256(scenarios: list[dict[str, Any]], split: str) -> str:
    membership = [
        {
            "scenario_id": scenario["scenario_id"],
            "family_id": scenario["family_id"],
            "question_ids": [question["question_id"] for question in scenario["questions"]],
        }
        for scenario in scenarios
        if scenario["split"] == split
    ]
    return canonical_sha256(membership)


def _validate_environment(environment: Any) -> None:
    if not isinstance(environment, dict) or set(environment) != ENVIRONMENT_FIELDS:
        raise ArtifactPackageError("environment must contain the exact reproducibility fields")
    if any(value is None or value == "" for value in environment.values()):
        raise ArtifactPackageError("environment reproducibility fields must be populated")


def _validate_started_at(value: Any) -> None:
    if not isinstance(value, str) or not value:
        raise ArtifactPackageError("started_at must be non-empty RFC 3339 text")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ArtifactPackageError("started_at must be valid RFC 3339 text") from exc
    if parsed.tzinfo is None:
        raise ArtifactPackageError("started_at must include an offset")


def build_run_manifest(
    *,
    run_id: str,
    started_at: str,
    git_commit: str,
    dirty_worktree: bool,
    dataset_manifest_path: Path,
    split: str,
    campaign_kind: str,
    repetitions: int,
    models: dict[str, Any],
    environment: dict[str, Any],
    pricing_snapshot: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build a manifest whose comparison configuration has a stable canonical hash."""

    dataset_manifest, scenarios = load_dataset(dataset_manifest_path)
    if RUN_ID_PATTERN.fullmatch(run_id) is None:
        raise ArtifactPackageError("run_id contains unsupported characters")
    _validate_started_at(started_at)
    if GIT_COMMIT_PATTERN.fullmatch(git_commit) is None:
        raise ArtifactPackageError("git_commit must be a full lowercase Git SHA")
    if not isinstance(dirty_worktree, bool):
        raise ArtifactPackageError("dirty_worktree must be boolean")
    if campaign_kind not in CAMPAIGN_KINDS:
        raise ArtifactPackageError("campaign_kind must be SMOKE or FROZEN_TEST")
    expected_repetitions = dataset_manifest["sampling"][
        "smoke_repetitions" if campaign_kind == "SMOKE" else "formal_test_repetitions"
    ]
    if repetitions != expected_repetitions:
        raise ArtifactPackageError("repetitions differ from the frozen campaign contract")
    if campaign_kind == "FROZEN_TEST" and split != "test":
        raise ArtifactPackageError("FROZEN_TEST must use the frozen test split")
    if models != dataset_manifest["selected_models"]:
        raise ArtifactPackageError("models differ from the frozen dataset contract")
    _validate_environment(environment)
    expected_case_rows(dataset_manifest, scenarios, split, repetitions)
    comparison = {
        "dataset": {
            "version": dataset_manifest["dataset_version"],
            "manifest_sha256": text_sha256(dataset_manifest_path),
            "case_sha256": dataset_manifest["case_sha256"],
            "split_membership_sha256": split_membership_sha256(scenarios, split),
        },
        "execution": {
            "split": split,
            "baselines": dataset_manifest["baselines"],
            "repetitions": repetitions,
        },
        "models": models,
        "prompts": dataset_manifest["prompts"],
        "sampling": dataset_manifest["sampling"],
        "limits": dataset_manifest["limits"],
        "pricing_snapshot": pricing_snapshot,
    }
    return {
        "schema_version": RUN_SCHEMA,
        "run_id": run_id,
        "started_at": started_at,
        "campaign_kind": campaign_kind,
        "git": {"commit": git_commit, "dirty_worktree": dirty_worktree},
        **comparison,
        "environment": environment,
        "comparison_config_sha256": canonical_sha256(comparison),
    }


def write_package(
    run_dir: Path,
    *,
    manifest: dict[str, Any],
    cases: list[dict[str, Any]],
    writes: list[dict[str, Any]],
    retrieval: list[dict[str, Any]],
    answers: list[dict[str, Any]],
    timings: list[dict[str, Any]],
    costs: dict[str, Any],
    metrics: dict[str, Any],
) -> None:
    """Create a package once; existing paths are never overwritten."""

    try:
        run_dir.mkdir(parents=True, exist_ok=False)
        _write_json(run_dir / "manifest.json", manifest)
        _write_jsonl(run_dir / "cases.jsonl", cases)
        _write_jsonl(run_dir / "writes.jsonl", writes)
        _write_jsonl(run_dir / "retrieval.jsonl", retrieval)
        _write_jsonl(run_dir / "answers.jsonl", answers)
        _write_jsonl(run_dir / "timings.jsonl", timings)
        _write_json(run_dir / "costs.json", costs)
        _write_json(run_dir / "metrics.json", metrics)
        with (run_dir / "failures.md").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(render_failures_markdown(answers, retrieval, timings))
        hashes = {name: file_sha256(run_dir / name) for name in sorted(PACKAGE_FILES)}
        integrity = {
            "schema_version": INTEGRITY_SCHEMA,
            "files": hashes,
            "package_sha256": canonical_sha256(hashes),
        }
        _write_json(run_dir / "integrity.json", integrity)
    except OSError as exc:
        raise ArtifactPackageError(f"cannot create immutable run package {run_dir}: {exc}") from exc


def _validate_manifest(
    manifest: dict[str, Any], dataset_manifest: dict[str, Any], dataset_manifest_path: Path
) -> None:
    required = {
        "schema_version",
        "run_id",
        "started_at",
        "campaign_kind",
        "git",
        "dataset",
        "execution",
        "models",
        "prompts",
        "sampling",
        "limits",
        "pricing_snapshot",
        "environment",
        "comparison_config_sha256",
    }
    if set(manifest) != required:
        raise ArtifactPackageError("run manifest has an unexpected schema")
    if manifest["schema_version"] != RUN_SCHEMA or manifest["campaign_kind"] not in CAMPAIGN_KINDS:
        raise ArtifactPackageError("run manifest version or campaign kind is invalid")
    if RUN_ID_PATTERN.fullmatch(str(manifest["run_id"])) is None:
        raise ArtifactPackageError("run manifest run_id is invalid")
    _validate_started_at(manifest["started_at"])
    _validate_environment(manifest["environment"])
    git = manifest["git"]
    if (
        not isinstance(git, dict)
        or set(git) != {"commit", "dirty_worktree"}
        or GIT_COMMIT_PATTERN.fullmatch(str(git["commit"])) is None
        or not isinstance(git["dirty_worktree"], bool)
    ):
        raise ArtifactPackageError("run manifest git identity is invalid")
    if git["dirty_worktree"]:
        raise ArtifactPackageError("dirty-worktree runs are ineligible")
    execution = manifest["execution"]
    if (
        not isinstance(execution, dict)
        or execution.get("baselines") != dataset_manifest["baselines"]
        or execution.get("split") not in {"train", "dev", "test"}
        or not isinstance(execution.get("repetitions"), int)
        or isinstance(execution.get("repetitions"), bool)
        or execution["repetitions"] < 1
    ):
        raise ArtifactPackageError("run manifest execution contract is invalid")
    dataset = manifest["dataset"]
    scenarios = load_dataset(dataset_manifest_path)[1]
    if not isinstance(dataset, dict) or dataset != {
        "version": dataset_manifest["dataset_version"],
        "manifest_sha256": text_sha256(dataset_manifest_path),
        "case_sha256": dataset_manifest["case_sha256"],
        "split_membership_sha256": split_membership_sha256(scenarios, execution["split"]),
    }:
        raise ArtifactPackageError("run manifest dataset identity differs from the frozen dataset")
    expected_repetitions = dataset_manifest["sampling"][
        "smoke_repetitions" if manifest["campaign_kind"] == "SMOKE" else "formal_test_repetitions"
    ]
    if execution["repetitions"] != expected_repetitions:
        raise ArtifactPackageError("run repetitions differ from the frozen campaign contract")
    if manifest["campaign_kind"] == "FROZEN_TEST" and execution["split"] != "test":
        raise ArtifactPackageError("FROZEN_TEST must use the frozen test split")
    if manifest["models"] != dataset_manifest["selected_models"]:
        raise ArtifactPackageError("run models differ from the frozen dataset contract")
    for field in ("prompts", "sampling", "limits"):
        if manifest[field] != dataset_manifest[field]:
            raise ArtifactPackageError(f"run {field} differ from the frozen dataset contract")
    if manifest["pricing_snapshot"] is not None:
        raise ArtifactPackageError("the frozen local-model path has no monetary pricing snapshot")
    comparison = {
        field: manifest[field]
        for field in (
            "dataset",
            "execution",
            "models",
            "prompts",
            "sampling",
            "limits",
            "pricing_snapshot",
        )
    }
    expected_hash = canonical_sha256(comparison)
    if manifest["comparison_config_sha256"] != expected_hash:
        raise ArtifactPackageError("run manifest comparison_config_sha256 mismatch")


def _validate_integrity(run_dir: Path) -> None:
    actual_files = {path.name for path in run_dir.iterdir() if path.is_file()}
    if actual_files != ALL_FILES:
        raise ArtifactPackageError(
            f"run package file set mismatch: missing={sorted(ALL_FILES - actual_files)} "
            f"unexpected={sorted(actual_files - ALL_FILES)}"
        )
    integrity = _read_json(run_dir / "integrity.json")
    if integrity.get("schema_version") != INTEGRITY_SCHEMA:
        raise ArtifactPackageError("integrity schema version is invalid")
    expected = integrity.get("files")
    if not isinstance(expected, dict) or set(expected) != PACKAGE_FILES:
        raise ArtifactPackageError("integrity file set is invalid")
    for name, digest in expected.items():
        if not isinstance(digest, str) or SHA256_PATTERN.fullmatch(digest) is None:
            raise ArtifactPackageError(f"integrity digest for {name} is invalid")
        if file_sha256(run_dir / name) != digest:
            raise ArtifactPackageError(f"artifact SHA-256 mismatch for {name}")
    if integrity.get("package_sha256") != canonical_sha256(expected):
        raise ArtifactPackageError("integrity package_sha256 mismatch")


def _validate_status_rows(
    expected: set[tuple[str, str, str, int]],
    rows: list[dict[str, Any]],
    name: str,
) -> None:
    try:
        indexed = indexed_rows(rows, name)
    except BenchmarkMetricError as exc:
        raise ArtifactPackageError(str(exc)) from exc
    if set(indexed) != expected:
        raise ArtifactPackageError(
            f"{name} execution set mismatch: missing={len(expected - set(indexed))} "
            f"unexpected={len(set(indexed) - expected)}"
        )
    for key, row in indexed.items():
        status = row.get("status")
        if status not in {"SUCCESS", "FAILED", "EXCLUDED"}:
            raise ArtifactPackageError(f"{name} row {key} has an invalid status")
        if status != "SUCCESS" and not isinstance(row.get("error_class"), str):
            raise ArtifactPackageError(f"{name} row {key} lacks explicit failure/exclusion reason")


def _usage(row: dict[str, Any]) -> dict[str, int]:
    fields = ("input_tokens", "output_tokens", "embedding_tokens", "model_calls")
    usage = row.get("usage")
    if usage is None:
        return dict.fromkeys(fields, 0)
    if not isinstance(usage, dict) or set(usage) != set(fields):
        raise ArtifactPackageError("usage must contain the exact v1 accounting fields")
    for field in fields:
        value = usage[field]
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise ArtifactPackageError(f"usage.{field} must be a non-negative integer")
    return usage


def generate_costs(
    run_manifest: dict[str, Any],
    writes: list[dict[str, Any]],
    retrieval: list[dict[str, Any]],
    answers: list[dict[str, Any]],
) -> dict[str, Any]:
    """Aggregate attributable model usage; local monetary cost remains unestablished."""

    fields = ("input_tokens", "output_tokens", "embedding_tokens", "model_calls")
    baselines = run_manifest["execution"]["baselines"]
    totals = {baseline: dict.fromkeys(fields, 0) for baseline in baselines}
    for row in [*writes, *retrieval, *answers]:
        baseline = row.get("baseline")
        if baseline not in totals:
            raise ArtifactPackageError("usage row has an unknown baseline")
        usage = _usage(row)
        for field in fields:
            totals[baseline][field] += usage[field]
    return {
        "schema_version": "memos-benchmark-costs.v1",
        "pricing_snapshot": run_manifest["pricing_snapshot"],
        "estimated_cost_usd": None,
        "by_baseline": totals,
        "disclaimer": (
            "Local model calls and tokens are counted, but monetary and energy costs are not "
            "established."
        ),
    }


def render_failures_markdown(
    answers: list[dict[str, Any]],
    retrieval: list[dict[str, Any]],
    timings: list[dict[str, Any]],
) -> str:
    """Render content-safe failure/exclusion accounting from raw status rows."""

    failures: list[tuple[str, tuple[str, str, str, int], str, str]] = []
    for stage, rows in (("answer", answers), ("retrieval", retrieval), ("timing", timings)):
        for row in rows:
            if row.get("status") == "SUCCESS":
                continue
            key = execution_key(row)
            error_class = row.get("error_class")
            if not isinstance(error_class, str) or not error_class:
                raise ArtifactPackageError(f"{stage} row {key} lacks an error_class")
            failures.append((stage, key, str(row.get("status")), error_class))
    lines = ["# Failures", ""]
    if not failures:
        return "\n".join([*lines, "No failed or excluded execution rows.", ""])
    lines.extend(
        [
            "| Stage | Baseline | Scenario | Question | Repetition | Status | Error class |",
            "|---|---|---|---|---:|---|---|",
        ]
    )
    for stage, key, status, error_class in sorted(failures):
        baseline, scenario_id, question_id, repetition = key
        safe_error = error_class.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
        lines.append(
            f"| {stage} | {baseline} | {scenario_id} | {question_id} | {repetition} | "
            f"{status} | {safe_error} |"
        )
    lines.append("")
    return "\n".join(lines)


def verify_package(run_dir: Path, dataset_manifest_path: Path) -> dict[str, Any]:
    """Verify hashes, coverage, raw status accounting, and regenerated metrics."""

    if not run_dir.is_dir():
        raise ArtifactPackageError(f"run directory does not exist: {run_dir}")
    _validate_integrity(run_dir)
    dataset_manifest, scenarios = load_dataset(dataset_manifest_path)
    manifest = _read_json(run_dir / "manifest.json")
    _validate_manifest(manifest, dataset_manifest, dataset_manifest_path)
    expected_cases = expected_case_rows(
        dataset_manifest,
        scenarios,
        manifest["execution"]["split"],
        manifest["execution"]["repetitions"],
    )
    actual_cases = _read_jsonl(run_dir / "cases.jsonl")
    if actual_cases != expected_cases:
        raise ArtifactPackageError("cases.jsonl differs from the frozen execution expansion")
    expected = {execution_key(row) for row in expected_cases}
    answers = _read_jsonl(run_dir / "answers.jsonl")
    retrieval = _read_jsonl(run_dir / "retrieval.jsonl")
    timings = _read_jsonl(run_dir / "timings.jsonl")
    writes = _read_jsonl(run_dir / "writes.jsonl")
    _validate_status_rows(expected, answers, "answers")
    _validate_status_rows(expected, retrieval, "retrieval")
    _validate_status_rows(expected, timings, "timings")
    try:
        regenerated = generate_metrics(
            dataset_manifest, scenarios, manifest, answers, retrieval, timings
        )
    except BenchmarkMetricError as exc:
        raise ArtifactPackageError(str(exc)) from exc
    if _read_json(run_dir / "metrics.json") != regenerated:
        raise ArtifactPackageError("metrics.json differs from mechanically regenerated metrics")
    if _read_json(run_dir / "costs.json") != generate_costs(manifest, writes, retrieval, answers):
        raise ArtifactPackageError("costs.json differs from mechanically regenerated usage")
    expected_failures = render_failures_markdown(answers, retrieval, timings)
    if (run_dir / "failures.md").read_text(encoding="utf-8") != expected_failures:
        raise ArtifactPackageError("failures.md differs from raw failure/exclusion rows")
    return {
        "run_id": manifest["run_id"],
        "campaign_kind": manifest["campaign_kind"],
        "execution_count": len(expected),
        "answer_status": dict(sorted(Counter(row["status"] for row in answers).items())),
        "package_sha256": _read_json(run_dir / "integrity.json")["package_sha256"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify an immutable MemOS benchmark run package")
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--dataset-manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        summary = verify_package(args.run_dir, args.dataset_manifest)
    except ArtifactPackageError as exc:
        parser.error(str(exc))
    print(canonical_json(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
