from __future__ import annotations

import json
from pathlib import Path

import pytest

from memos_benchmark.dataset import BenchmarkDatasetError, load_dataset, main, text_sha256

DATASET_DIR = Path(__file__).parents[1] / "datasets" / "memos-assistant-smoke" / "v1"
MANIFEST = DATASET_DIR / "manifest.json"


def _copy_dataset(tmp_path: Path) -> tuple[Path, dict[str, object], dict[str, object]]:
    target = tmp_path / "dataset"
    target.mkdir(parents=True)
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    cases = json.loads((DATASET_DIR / "cases.json").read_text(encoding="utf-8"))
    prompts = tmp_path / "prompts" / "v1"
    prompts.mkdir(parents=True)
    source_prompts = Path(__file__).parents[1] / "prompts" / "v1"
    for name in ("answer.txt", "summary.txt"):
        (prompts / name).write_text(
            (source_prompts / name).read_text(encoding="utf-8"), encoding="utf-8"
        )
    manifest["prompts"]["answer_file"] = "../prompts/v1/answer.txt"  # type: ignore[index]
    manifest["prompts"]["summary_file"] = "../prompts/v1/summary.txt"  # type: ignore[index]
    schemas = tmp_path / "schemas" / "v1"
    schemas.mkdir(parents=True)
    source_schemas = Path(__file__).parents[1] / "schemas" / "v1"
    for name in ("answer.schema.json", "summary.schema.json"):
        (schemas / name).write_text(
            (source_schemas / name).read_text(encoding="utf-8"), encoding="utf-8"
        )
    manifest["prompts"]["answer_schema_file"] = "../schemas/v1/answer.schema.json"  # type: ignore[index]
    manifest["prompts"]["summary_schema_file"] = "../schemas/v1/summary.schema.json"  # type: ignore[index]
    source_extraction = (
        Path(__file__).parents[2]
        / "modules"
        / "adapters"
        / "src"
        / "main"
        / "resources"
        / "providers"
        / "openai-compatible"
    )
    for name in ("candidate-extraction-v1.txt", "memory-candidate-v1.schema.json"):
        (target / name).write_text(
            (source_extraction / name).read_text(encoding="utf-8"), encoding="utf-8"
        )
    manifest["prompts"]["extraction_file"] = "candidate-extraction-v1.txt"  # type: ignore[index]
    manifest["prompts"]["extraction_schema_file"] = (  # type: ignore[index]
        "memory-candidate-v1.schema.json"
    )
    for name in ("LICENSE.txt", "NOTICE.md"):
        (target / name).write_text(
            (DATASET_DIR / name).read_text(encoding="utf-8"), encoding="utf-8"
        )
    return target, manifest, cases


def _write_copy(target: Path, manifest: dict[str, object], cases: dict[str, object]) -> Path:
    case_path = target / "cases.json"
    case_path.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest["case_file"] = "cases.json"
    manifest["case_sha256"] = text_sha256(case_path)
    manifest_path = target / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest_path


def test_frozen_dataset_manifest_is_integral() -> None:
    manifest, scenarios = load_dataset(MANIFEST)

    assert manifest["dataset_version"] == "memos-assistant-smoke-v1"
    assert len(scenarios) == 13
    assert sum(len(scenario["questions"]) for scenario in scenarios) == 15
    assert {
        scenario["scenario_id"] for scenario in scenarios if scenario["split"] == "test"
    } == set(manifest["frozen_test_scenario_ids"])


def test_checksum_drift_fails_closed(tmp_path: Path) -> None:
    target, manifest, cases = _copy_dataset(tmp_path)
    manifest_path = _write_copy(target, manifest, cases)
    manifest["case_sha256"] = "0" * 64
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(BenchmarkDatasetError, match="case SHA-256 mismatch"):
        load_dataset(manifest_path)


def test_license_notice_drift_fails_closed(tmp_path: Path) -> None:
    target, manifest, cases = _copy_dataset(tmp_path)
    manifest_path = _write_copy(target, manifest, cases)
    (target / "NOTICE.md").write_text("changed attribution\n", encoding="utf-8")

    with pytest.raises(BenchmarkDatasetError, match="notice SHA-256 mismatch"):
        load_dataset(manifest_path)


def test_extraction_prompt_drift_fails_closed(tmp_path: Path) -> None:
    target, manifest, cases = _copy_dataset(tmp_path)
    manifest_path = _write_copy(target, manifest, cases)
    (target / "candidate-extraction-v1.txt").write_text("changed prompt\n", encoding="utf-8")

    with pytest.raises(BenchmarkDatasetError, match="prompts.extraction SHA-256 mismatch"):
        load_dataset(manifest_path)


def test_family_leakage_and_future_evidence_fail_closed(tmp_path: Path) -> None:
    target, manifest, cases = _copy_dataset(tmp_path)
    scenarios = cases["scenarios"]  # type: ignore[index]
    scenarios[0]["family_id"] = scenarios[2]["family_id"]  # type: ignore[index]
    manifest_path = _write_copy(target, manifest, cases)
    with pytest.raises(BenchmarkDatasetError, match="families cross splits"):
        load_dataset(manifest_path)

    target, manifest, cases = _copy_dataset(tmp_path / "future")
    scenario = cases["scenarios"][5]  # type: ignore[index]
    scenario["questions"][0]["after_event_id"] = scenario["sessions"][0]["events"][0]["event_id"]
    manifest_path = _write_copy(target, manifest, cases)
    with pytest.raises(BenchmarkDatasetError, match="future/unknown gold evidence"):
        load_dataset(manifest_path)


def test_cli_reports_verified_counts(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["--manifest", str(MANIFEST)]) == 0
    assert "13 scenarios, 15 questions" in capsys.readouterr().out
