"""Integrity gate for versioned MemOS benchmark datasets."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

SPLITS = {"train", "dev", "test"}
ACTOR_TYPES = {"USER", "ASSISTANT", "TOOL", "APPLICATION", "SYSTEM", "WEB"}
SOURCE_TYPES = {"CONVERSATION_MESSAGE", "TOOL_RESULT", "APPLICATION_EVENT", "DIRECT_MEMORY_COMMAND"}
TRUST_LEVELS = {"DIRECT_USER", "TRUSTED_APPLICATION", "ASSISTANT_GENERATED", "EXTERNAL_UNTRUSTED"}
ANSWER_KINDS = {"TEXT", "SET", "ABSTAIN"}
TEMPORAL_INTENTS = {"NONE", "PRESENT", "HISTORICAL", "AS_OF"}
TEXT_HASH_MODE = "sha256-utf8-lf-v1"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


class BenchmarkDatasetError(ValueError):
    """Raised when a dataset or manifest is not eligible to run."""


def _read_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise BenchmarkDatasetError(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise BenchmarkDatasetError(f"expected a JSON object in {path}")
    return value


def text_sha256(path: Path) -> str:
    """Hash UTF-8 text after normalizing line endings to LF."""

    try:
        normalized = path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
    except OSError as exc:
        raise BenchmarkDatasetError(f"cannot read hashed file {path}: {exc}") from exc
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _require_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BenchmarkDatasetError(f"{field} must be non-empty text")
    return value


def _resolve_file(manifest_path: Path, value: Any, field: str) -> Path:
    relative = Path(_require_text(value, field))
    if relative.is_absolute():
        raise BenchmarkDatasetError(f"{field} must be relative to the manifest")
    resolved = (manifest_path.parent / relative).resolve()
    if not resolved.is_file():
        raise BenchmarkDatasetError(f"{field} does not resolve to a file")
    return resolved


def _validate_hashed_file(
    manifest_path: Path,
    file_value: Any,
    hash_value: Any,
    field: str,
) -> Path:
    path = _resolve_file(manifest_path, file_value, f"{field}_file")
    expected = _require_text(hash_value, f"{field}_sha256")
    if SHA256_PATTERN.fullmatch(expected) is None:
        raise BenchmarkDatasetError(f"{field}_sha256 must be a lowercase SHA-256 digest")
    actual = text_sha256(path)
    if actual != expected:
        raise BenchmarkDatasetError(
            f"{field} SHA-256 mismatch: manifest={expected} actual={actual}"
        )
    return path


def load_dataset(manifest_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Load a manifest and fail closed on split, cutoff, or hash drift."""

    manifest_path = manifest_path.resolve()
    manifest = _read_object(manifest_path)
    required_manifest = {
        "dataset_version",
        "dataset_kind",
        "workload",
        "license",
        "license_file",
        "license_sha256",
        "notice_file",
        "notice_sha256",
        "text_hash_mode",
        "contains_real_personal_data",
        "case_file",
        "case_sha256",
        "scenario_count",
        "question_count",
        "splits",
        "required_tracks",
        "frozen_test_scenario_ids",
        "prompts",
        "selected_models",
        "sampling",
        "limits",
        "baselines",
    }
    missing = sorted(required_manifest - manifest.keys())
    if missing:
        raise BenchmarkDatasetError(f"manifest missing fields: {missing}")
    if manifest["contains_real_personal_data"] is not False:
        raise BenchmarkDatasetError("the smoke dataset must not contain real personal data")
    if manifest["license"] != "CC-BY-4.0":
        raise BenchmarkDatasetError("the smoke dataset license must be CC-BY-4.0")
    if manifest["text_hash_mode"] != TEXT_HASH_MODE:
        raise BenchmarkDatasetError(f"text_hash_mode must be {TEXT_HASH_MODE}")

    _validate_hashed_file(
        manifest_path,
        manifest["license_file"],
        manifest["license_sha256"],
        "license",
    )
    _validate_hashed_file(
        manifest_path,
        manifest["notice_file"],
        manifest["notice_sha256"],
        "notice",
    )

    case_path = _resolve_file(manifest_path, manifest["case_file"], "case_file")
    actual_case_sha = text_sha256(case_path)
    if actual_case_sha != manifest["case_sha256"]:
        raise BenchmarkDatasetError(
            f"case SHA-256 mismatch: manifest={manifest['case_sha256']} actual={actual_case_sha}"
        )
    case_document = _read_object(case_path)
    if case_document.get("dataset_version") != manifest["dataset_version"]:
        raise BenchmarkDatasetError("dataset version differs between manifest and case file")
    scenarios = case_document.get("scenarios")
    if not isinstance(scenarios, list):
        raise BenchmarkDatasetError("scenarios must be an array")

    _validate_prompt_hashes(manifest_path, manifest)
    _validate_scenarios(manifest, scenarios)
    return manifest, scenarios


def _validate_prompt_hashes(manifest_path: Path, manifest: dict[str, Any]) -> None:
    prompts = manifest["prompts"]
    if not isinstance(prompts, dict):
        raise BenchmarkDatasetError("prompts must be an object")
    for name in ("answer", "summary"):
        _validate_hashed_file(
            manifest_path,
            prompts.get(f"{name}_file"),
            prompts.get(f"{name}_sha256"),
            f"prompts.{name}",
        )


def _validate_scenarios(manifest: dict[str, Any], scenarios: list[dict[str, Any]]) -> None:
    if len(scenarios) != manifest["scenario_count"]:
        raise BenchmarkDatasetError("manifest scenario_count does not match cases")
    scenario_ids: list[str] = []
    question_ids: list[str] = []
    event_ids: list[str] = []
    families: dict[str, set[str]] = defaultdict(set)
    split_scenarios: Counter[str] = Counter()
    split_questions: Counter[str] = Counter()
    tracks: set[str] = set()

    for scenario in scenarios:
        if not isinstance(scenario, dict):
            raise BenchmarkDatasetError("every scenario must be an object")
        scenario_id = _require_text(scenario.get("scenario_id"), "scenario_id")
        family_id = _require_text(scenario.get("family_id"), f"scenario {scenario_id} family_id")
        split = scenario.get("split")
        if split not in SPLITS:
            raise BenchmarkDatasetError(f"scenario {scenario_id} has an invalid split")
        scenario_ids.append(scenario_id)
        families[family_id].add(split)
        split_scenarios[split] += 1
        scenario_tracks = scenario.get("tracks")
        if (
            not isinstance(scenario_tracks, list)
            or not scenario_tracks
            or any(not isinstance(track, str) or not track for track in scenario_tracks)
            or len(scenario_tracks) != len(set(scenario_tracks))
        ):
            raise BenchmarkDatasetError(f"scenario {scenario_id} has invalid tracks")
        tracks.update(scenario_tracks)
        _validate_scope(scenario_id, scenario.get("scope"))
        local_events, local_questions = _validate_scenario_content(scenario_id, scenario)
        event_ids.extend(local_events)
        question_ids.extend(local_questions)
        split_questions[split] += len(local_questions)

    _require_unique(scenario_ids, "scenario_id")
    _require_unique(event_ids, "event_id")
    _require_unique(question_ids, "question_id")
    leaking_families = sorted(family for family, splits in families.items() if len(splits) != 1)
    if leaking_families:
        raise BenchmarkDatasetError(f"scenario families cross splits: {leaking_families}")
    _validate_counts(manifest, split_scenarios, split_questions, len(question_ids))
    required_tracks = set(manifest["required_tracks"])
    if not required_tracks.issubset(tracks):
        raise BenchmarkDatasetError(f"missing required tracks: {sorted(required_tracks - tracks)}")
    frozen = set(manifest["frozen_test_scenario_ids"])
    actual_test = {scenario["scenario_id"] for scenario in scenarios if scenario["split"] == "test"}
    if frozen != actual_test:
        raise BenchmarkDatasetError("frozen_test_scenario_ids must equal the complete test split")


def _validate_scope(scenario_id: str, scope: Any) -> None:
    required = {"tenant_id", "user_id", "agent_id"}
    if not isinstance(scope, dict) or set(scope) != required:
        raise BenchmarkDatasetError(f"scenario {scenario_id} requires an exact hard scope")
    for field, value in scope.items():
        _require_text(value, f"scenario {scenario_id} scope.{field}")


def _validate_scenario_content(
    scenario_id: str, scenario: dict[str, Any]
) -> tuple[list[str], list[str]]:
    sessions = scenario.get("sessions")
    questions = scenario.get("questions")
    if not isinstance(sessions, list) or not sessions:
        raise BenchmarkDatasetError(f"scenario {scenario_id} requires sessions")
    if not isinstance(questions, list) or not questions:
        raise BenchmarkDatasetError(f"scenario {scenario_id} requires questions")

    ordered_events: list[str] = []
    for session in sessions:
        if not isinstance(session, dict):
            raise BenchmarkDatasetError(f"scenario {scenario_id} has an invalid session")
        _require_text(session.get("session_id"), f"scenario {scenario_id} session_id")
        events = session.get("events")
        if not isinstance(events, list) or not events:
            raise BenchmarkDatasetError(f"scenario {scenario_id} has an empty session")
        for event in events:
            _validate_event(scenario_id, event)
            ordered_events.append(event["event_id"])
    _require_unique(ordered_events, f"scenario {scenario_id} event_id")
    event_positions = {event_id: index for index, event_id in enumerate(ordered_events)}

    question_ids: list[str] = []
    for question in questions:
        if not isinstance(question, dict):
            raise BenchmarkDatasetError(f"scenario {scenario_id} has an invalid question")
        question_id = _require_text(
            question.get("question_id"), f"scenario {scenario_id} question_id"
        )
        question_ids.append(question_id)
        _require_text(question.get("query"), f"question {question_id} query")
        cutoff = _require_text(question.get("after_event_id"), f"question {question_id} cutoff")
        if cutoff not in event_positions:
            raise BenchmarkDatasetError(f"question {question_id} cutoff is not in its scenario")
        _validate_answer(question_id, question)
        gold = question.get("gold_event_ids")
        if not isinstance(gold, list) or len(gold) != len(set(gold)):
            raise BenchmarkDatasetError(f"question {question_id} has invalid gold_event_ids")
        future = [
            event_id
            for event_id in gold
            if event_id not in event_positions
            or event_positions[event_id] > event_positions[cutoff]
        ]
        if future:
            raise BenchmarkDatasetError(
                f"question {question_id} references future/unknown evidence"
            )
    _require_unique(question_ids, f"scenario {scenario_id} question_id")
    return ordered_events, question_ids


def _validate_event(scenario_id: str, event: Any) -> None:
    if not isinstance(event, dict):
        raise BenchmarkDatasetError(f"scenario {scenario_id} has a non-object event")
    event_id = _require_text(event.get("event_id"), f"scenario {scenario_id} event_id")
    _require_text(event.get("occurred_at"), f"event {event_id} occurred_at")
    _require_text(event.get("content"), f"event {event_id} content")
    if event.get("actor_type") not in ACTOR_TYPES:
        raise BenchmarkDatasetError(f"event {event_id} has an invalid actor_type")
    if event.get("source_type") not in SOURCE_TYPES:
        raise BenchmarkDatasetError(f"event {event_id} has an invalid source_type")
    if event.get("trust_level") not in TRUST_LEVELS:
        raise BenchmarkDatasetError(f"event {event_id} has an invalid trust_level")


def _validate_answer(question_id: str, question: dict[str, Any]) -> None:
    answer = question.get("answer")
    if not isinstance(answer, dict) or set(answer) != {"kind", "acceptable"}:
        raise BenchmarkDatasetError(f"question {question_id} has an invalid answer object")
    kind = answer.get("kind")
    acceptable = answer.get("acceptable")
    if kind not in ANSWER_KINDS or not isinstance(acceptable, list):
        raise BenchmarkDatasetError(f"question {question_id} has an invalid answer label")
    if any(not isinstance(value, str) or not value for value in acceptable):
        raise BenchmarkDatasetError(f"question {question_id} has invalid acceptable values")
    must_abstain = question.get("must_abstain")
    if not isinstance(must_abstain, bool) or must_abstain != (kind == "ABSTAIN"):
        raise BenchmarkDatasetError(f"question {question_id} abstention label is inconsistent")
    if kind == "ABSTAIN" and acceptable:
        raise BenchmarkDatasetError(f"question {question_id} abstention answer must be empty")
    if kind != "ABSTAIN" and not acceptable:
        raise BenchmarkDatasetError(f"question {question_id} requires acceptable answers")
    if question.get("temporal_intent") not in TEMPORAL_INTENTS:
        raise BenchmarkDatasetError(f"question {question_id} has an invalid temporal_intent")
    forbidden = question.get("forbidden_answers")
    if not isinstance(forbidden, list) or any(not isinstance(value, str) for value in forbidden):
        raise BenchmarkDatasetError(f"question {question_id} has invalid forbidden_answers")


def _require_unique(values: list[str], field: str) -> None:
    duplicates = sorted(value for value, count in Counter(values).items() if count > 1)
    if duplicates:
        raise BenchmarkDatasetError(f"duplicate {field}: {duplicates}")


def _validate_counts(
    manifest: dict[str, Any],
    scenarios: Counter[str],
    questions: Counter[str],
    total_questions: int,
) -> None:
    if total_questions != manifest["question_count"]:
        raise BenchmarkDatasetError("manifest question_count does not match cases")
    expected = manifest["splits"]
    actual = {
        split: {"scenarios": scenarios[split], "questions": questions[split]}
        for split in sorted(SPLITS)
    }
    if actual != {split: expected[split] for split in sorted(SPLITS)}:
        raise BenchmarkDatasetError("manifest split counts do not match cases")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify a versioned MemOS benchmark dataset")
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        manifest, scenarios = load_dataset(args.manifest)
    except BenchmarkDatasetError as exc:
        parser.error(str(exc))
    question_count = sum(len(scenario["questions"]) for scenario in scenarios)
    print(
        f"verified {manifest['dataset_version']}: "
        f"{len(scenarios)} scenarios, {question_count} questions"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
