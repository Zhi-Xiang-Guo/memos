from __future__ import annotations

import json
from pathlib import Path

import pytest

from memos_benchmark.artifacts import (
    ArtifactPackageError,
    build_run_manifest,
    expected_case_rows,
    expected_write_keys,
    generate_costs,
    verify_package,
    write_package,
)
from memos_benchmark.dataset import load_dataset
from memos_benchmark.metrics import generate_metrics

DATASET_MANIFEST = (
    Path(__file__).parents[1] / "datasets" / "memos-assistant-smoke" / "v1" / "manifest.json"
)


def _question_map(scenarios: list[dict[str, object]]) -> dict[tuple[str, str], dict[str, object]]:
    return {
        (scenario["scenario_id"], question["question_id"]): question
        for scenario in scenarios
        for question in scenario["questions"]
    }


def _successful_rows(
    cases: list[dict[str, object]], scenarios: list[dict[str, object]]
) -> tuple[list[dict[str, object]], list[dict[str, object]], list[dict[str, object]]]:
    questions = _question_map(scenarios)
    answers: list[dict[str, object]] = []
    retrieval: list[dict[str, object]] = []
    timings: list[dict[str, object]] = []
    for index, case in enumerate(cases, start=1):
        identity = {
            field: case[field] for field in ("baseline", "scenario_id", "question_id", "repetition")
        }
        question = questions[(case["scenario_id"], case["question_id"])]
        answer = question["answer"]
        if answer["kind"] == "ABSTAIN":
            output = {
                "abstain": True,
                "answer": "",
                "items": [],
                "reason_code": "insufficient_or_conflicting_evidence",
                "citations": question["gold_event_ids"],
            }
        elif answer["kind"] == "SET":
            output = {
                "abstain": False,
                "answer": "",
                "items": sorted(answer["acceptable"]),
                "reason_code": "",
                "citations": question["gold_event_ids"],
            }
        else:
            output = {
                "abstain": False,
                "answer": answer["acceptable"][0],
                "items": [],
                "reason_code": "",
                "citations": question["gold_event_ids"],
            }
        answers.append(
            identity
            | {
                "status": "SUCCESS",
                "provided_evidence_ids": question["gold_event_ids"],
                "output": output,
                "usage": _zero_usage(),
                "usage_complete": True,
            }
        )
        retrieval.append(
            identity
            | {
                "status": "SUCCESS",
                "ranked_event_ids": question["gold_event_ids"],
                "selected_event_ids": question["gold_event_ids"],
                "context_tokens": len(question["gold_event_ids"]) * 10,
                "usage": _zero_usage(),
                "usage_complete": True,
            }
        )
        timings.append(
            identity
            | {
                "status": "SUCCESS",
                "total_ms": float(index),
                "stages_ms": {"answer": float(index)},
            }
        )
    return answers, retrieval, timings


def _run_data() -> tuple[
    dict[str, object],
    list[dict[str, object]],
    dict[str, object],
    list[dict[str, object]],
    list[dict[str, object]],
    list[dict[str, object]],
    list[dict[str, object]],
]:
    dataset_manifest, scenarios = load_dataset(DATASET_MANIFEST)
    manifest = build_run_manifest(
        run_id="20260830T000000Z-4120144-smoke",
        started_at="2026-08-30T00:00:00Z",
        git_commit="4" * 40,
        dirty_worktree=False,
        dataset_manifest_path=DATASET_MANIFEST,
        split="dev",
        campaign_kind="SMOKE",
        repetitions=1,
        models=dataset_manifest["selected_models"],
        environment={
            "python": "3.14.7",
            "java": "25-test",
            "postgres": "18-test",
            "pgvector": "test",
            "ollama": "0.33.2",
            "machine": "test",
        },
    )
    cases = expected_case_rows(dataset_manifest, scenarios, "dev", 1)
    answers, retrieval, timings = _successful_rows(cases, scenarios)
    return dataset_manifest, scenarios, manifest, cases, answers, retrieval, timings


def _write_valid_package(run_dir: Path) -> None:
    dataset_manifest, scenarios, manifest, cases, answers, retrieval, timings = _run_data()
    writes = _successful_write_rows(dataset_manifest, scenarios, manifest)
    metrics = generate_metrics(dataset_manifest, scenarios, manifest, answers, retrieval, timings)
    costs = generate_costs(manifest, writes, retrieval, answers)
    write_package(
        run_dir,
        manifest=manifest,
        cases=cases,
        writes=writes,
        retrieval=retrieval,
        answers=answers,
        timings=timings,
        costs=costs,
        metrics=metrics,
    )


def test_run_package_round_trip_is_integral(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    _write_valid_package(run_dir)

    summary = verify_package(run_dir, DATASET_MANIFEST)

    assert summary["execution_count"] == 12
    assert summary["answer_status"] == {"SUCCESS": 12}
    assert summary["usage_complete"] is True
    assert len(summary["package_sha256"]) == 64


def test_existing_package_and_content_drift_fail_closed(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    _write_valid_package(run_dir)

    with pytest.raises(ArtifactPackageError, match="cannot create immutable run package"):
        _write_valid_package(run_dir)

    answers = run_dir / "answers.jsonl"
    answers.write_text(answers.read_text(encoding="utf-8") + "{}\n", encoding="utf-8")
    with pytest.raises(ArtifactPackageError, match="SHA-256 mismatch"):
        verify_package(run_dir, DATASET_MANIFEST)


def test_dirty_worktree_manifest_is_ineligible(tmp_path: Path) -> None:
    dataset_manifest, scenarios, manifest, cases, answers, retrieval, timings = _run_data()
    manifest["git"]["dirty_worktree"] = True
    writes = _successful_write_rows(dataset_manifest, scenarios, manifest)
    metrics = generate_metrics(dataset_manifest, scenarios, manifest, answers, retrieval, timings)
    costs = generate_costs(manifest, writes, retrieval, answers)
    run_dir = tmp_path / "dirty"
    write_package(
        run_dir,
        manifest=manifest,
        cases=cases,
        writes=writes,
        retrieval=retrieval,
        answers=answers,
        timings=timings,
        costs=costs,
        metrics=metrics,
    )

    with pytest.raises(ArtifactPackageError, match="dirty-worktree"):
        verify_package(run_dir, DATASET_MANIFEST)


def test_metrics_reject_rehashed_manual_correction(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    _write_valid_package(run_dir)
    metrics_path = run_dir / "metrics.json"
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    metrics["baselines"]["full_history"]["answer"]["accuracy"] = 0.123
    metrics_path.write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    integrity_path = run_dir / "integrity.json"
    integrity = json.loads(integrity_path.read_text(encoding="utf-8"))
    from memos_benchmark.artifacts import canonical_sha256, file_sha256

    integrity["files"]["metrics.json"] = file_sha256(metrics_path)
    integrity["package_sha256"] = canonical_sha256(integrity["files"])
    integrity_path.write_text(
        json.dumps(integrity, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    with pytest.raises(ArtifactPackageError, match="mechanically regenerated"):
        verify_package(run_dir, DATASET_MANIFEST)


def test_write_coverage_and_explicit_usage_fail_closed(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    _write_valid_package(run_dir)
    writes_path = run_dir / "writes.jsonl"
    writes = [json.loads(line) for line in writes_path.read_text(encoding="utf-8").splitlines()]
    writes[0].pop("usage")
    writes_path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in writes), encoding="utf-8"
    )
    _rehash(run_dir, "writes.jsonl")

    with pytest.raises(ArtifactPackageError, match="every usage row"):
        verify_package(run_dir, DATASET_MANIFEST)


def test_write_execution_set_rejects_missing_rows(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    _write_valid_package(run_dir)
    writes_path = run_dir / "writes.jsonl"
    writes = writes_path.read_text(encoding="utf-8").splitlines()
    writes_path.write_text("\n".join(writes[1:]) + "\n", encoding="utf-8")
    _rehash(run_dir, "writes.jsonl")

    with pytest.raises(ArtifactPackageError, match="writes execution set mismatch"):
        verify_package(run_dir, DATASET_MANIFEST)


def _successful_write_rows(
    dataset_manifest: dict[str, object],
    scenarios: list[dict[str, object]],
    manifest: dict[str, object],
) -> list[dict[str, object]]:
    keys = expected_write_keys(
        dataset_manifest,
        scenarios,
        manifest["execution"]["split"],
        manifest["execution"]["repetitions"],
    )
    return [
        {
            "baseline": baseline,
            "scenario_id": scenario_id,
            "repetition": repetition,
            "status": "SUCCESS",
            "usage": _zero_usage(),
            "usage_complete": True,
        }
        for baseline, scenario_id, repetition in sorted(keys)
    ]


def _rehash(run_dir: Path, name: str) -> None:
    from memos_benchmark.artifacts import canonical_sha256, file_sha256

    integrity_path = run_dir / "integrity.json"
    integrity = json.loads(integrity_path.read_text(encoding="utf-8"))
    integrity["files"][name] = file_sha256(run_dir / name)
    integrity["package_sha256"] = canonical_sha256(integrity["files"])
    integrity_path.write_text(
        json.dumps(integrity, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def _zero_usage() -> dict[str, int]:
    return {
        "input_tokens": 0,
        "output_tokens": 0,
        "embedding_tokens": 0,
        "model_calls": 0,
    }
