"""Separate teaching report: retrieval evidence, answer correctness, and manual support labels.

This does not alter the frozen Feature 6 runner or produce a formal benchmark artifact.
"""

from __future__ import annotations

import argparse
import json
import unicodedata
from pathlib import Path
from typing import Any


def _ids(value: Any) -> list[str]:
    if (
        not isinstance(value, list)
        or any(not isinstance(item, str) or not item for item in value)
        or len(value) != len(set(value))
    ):
        raise ValueError("evidence IDs must be unique nonempty strings")
    return value


def _text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def evaluate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        raise ValueError("at least one labeled case is required")
    seen: set[str] = set()
    retrieval: list[tuple[float, float, float, float]] = []
    correct = valid_citations = complete_context = eligible_context = 0
    failed = tp = fp = fn = 0
    supported: list[bool] = []
    diagnostics = []
    for row in rows:
        case_id = row["case_id"]
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise ValueError("case_id must be unique and nonempty")
        seen.add(case_id)
        gold = set(_ids(row["gold_ids"]))
        ranked = _ids(row["ranked_ids"])
        selected = set(_ids(row["selected_ids"]))
        if not selected.issubset(ranked):
            raise ValueError("selected evidence must be a subset of ranked evidence")
        expected_abstain = row["must_abstain"]
        if type(expected_abstain) is not bool:
            raise ValueError("must_abstain must be boolean")
        expected_answers = row["accepted_answers"]
        if not isinstance(expected_answers, list) or any(
            not isinstance(item, str) or not item.strip() for item in expected_answers
        ):
            raise ValueError("accepted_answers must be strings")
        if not expected_abstain and (not expected_answers or not gold):
            raise ValueError("answerable cases need answers and gold evidence")
        status = row["status"]
        if status not in {"SUCCESS", "FAILED"}:
            raise ValueError("unsupported status")
        retrieval_status = row["retrieval_status"]
        if retrieval_status not in {"SUCCESS", "FAILED"}:
            raise ValueError("unsupported retrieval status")
        if retrieval_status == "FAILED":
            if ranked or selected:
                raise ValueError("failed retrieval must not claim evidence")
            if status == "SUCCESS":
                raise ValueError("retrieval failure is not a successful answer")
        if gold:
            hits = gold.intersection(ranked)
            positions = [ranked.index(item) + 1 for item in hits]
            retrieval.append(
                (
                    len(hits) / len(gold),
                    len(hits) / len(ranked) if ranked else 0.0,
                    float(bool(hits)),
                    1 / min(positions) if positions else 0.0,
                )
            )
            eligible_context += 1
            complete_context += gold.issubset(selected)
        prediction_abstain = False
        answer_correct = False
        if status == "FAILED":
            failed += 1
            if row.get("output") is not None:
                raise ValueError("failed answer must not provide a successful output")
        else:
            output = row["output"]
            if set(output) != {"answer", "abstain", "citations"}:
                raise ValueError("invalid answer schema")
            prediction_abstain = output["abstain"]
            if type(prediction_abstain) is not bool or not isinstance(output["answer"], str):
                raise ValueError("invalid answer types")
            citations = set(_ids(output["citations"]))
            citation_valid = (
                not citations if prediction_abstain else bool(citations) and citations <= selected
            )
            valid_citations += citation_valid
            answer_correct = (
                expected_abstain
                and prediction_abstain
                or not expected_abstain
                and not prediction_abstain
                and _text(output["answer"]) in {_text(item) for item in expected_answers}
            ) and citation_valid
            correct += answer_correct
            support = row.get("human_supported")
            if support is not None:
                if type(support) is not bool:
                    raise ValueError("human_supported must be boolean or null")
                supported.append(support)
        tp += expected_abstain and prediction_abstain
        fp += not expected_abstain and prediction_abstain
        fn += expected_abstain and not prediction_abstain
        diagnostics.append(
            {
                "case_id": case_id,
                "answer_correct": answer_correct,
                "failure_stage": (
                    "retrieval"
                    if retrieval_status == "FAILED" or gold - set(ranked)
                    else "context"
                    if gold - selected
                    else "generation"
                    if not answer_correct
                    else "none"
                ),
            }
        )
    count = len(rows)
    return {
        "report_kind": "PRACTICE_ONLY",
        "cases": count,
        "failed": failed,
        "retrieval": {
            "eligible": len(retrieval),
            **{
                name: round(sum(row[index] for row in retrieval) / len(retrieval), 6)
                if retrieval
                else None
                for index, name in enumerate(("recall_at_k", "precision_at_k", "hit_at_k", "mrr"))
            },
            "context_complete_rate": complete_context / eligible_context
            if eligible_context
            else None,
        },
        "answer": {
            "accuracy": correct / count,
            "citation_valid_rate": valid_citations / count,
            "abstention_f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
            "human_reviewed": len(supported),
            "human_supported_rate": sum(supported) / len(supported) if supported else None,
        },
        "diagnostics": diagnostics,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.input.read_text().splitlines() if line.strip()]
    report = evaluate(rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Practice report: {args.output} ({report['cases']} cases, not a formal benchmark)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
