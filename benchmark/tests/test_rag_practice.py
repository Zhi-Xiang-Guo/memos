import pytest

from memos_benchmark.rag_practice import evaluate


def case(**changes):
    row = {
        "case_id": "multi-hop",
        "gold_ids": ["a", "b"],
        "ranked_ids": ["a", "noise"],
        "selected_ids": ["a"],
        "must_abstain": False,
        "accepted_answers": ["Shanghai and Hangzhou"],
        "status": "SUCCESS",
        "retrieval_status": "SUCCESS",
        "output": {"answer": "Shanghai", "abstain": False, "citations": ["a"]},
    }
    return row | changes


def test_partial_multi_hop_hit_is_not_full_recall_or_correct_answer():
    result = evaluate([case()])
    assert result["retrieval"]["hit_at_k"] == 1
    assert result["retrieval"]["recall_at_k"] == 0.5
    assert result["retrieval"]["precision_at_k"] == 0.5
    assert result["answer"]["accuracy"] == 0
    assert result["answer"]["human_supported_rate"] is None


def test_correct_text_with_citation_outside_context_is_not_valid():
    result = evaluate(
        [case(output={"answer": "Shanghai and Hangzhou", "abstain": False, "citations": ["b"]})]
    )
    assert result["answer"]["accuracy"] == 0
    assert result["answer"]["citation_valid_rate"] == 0


def test_failed_generation_keeps_successful_retrieval_and_failure_denominator():
    result = evaluate(
        [case(ranked_ids=["a", "b"], selected_ids=["a", "b"], status="FAILED", output=None)]
    )
    assert result["retrieval"]["recall_at_k"] == 1
    assert result["answer"]["accuracy"] == 0
    assert result["failed"] == 1
    assert result["diagnostics"][0]["failure_stage"] == "generation"


def test_no_evidence_abstention_and_manual_support_labels():
    result = evaluate(
        [
            case(
                gold_ids=[],
                ranked_ids=[],
                selected_ids=[],
                must_abstain=True,
                accepted_answers=[],
                output={"answer": "Unknown", "abstain": True, "citations": []},
                human_supported=True,
            )
        ]
    )
    assert result["retrieval"]["recall_at_k"] is None
    assert result["answer"]["abstention_f1"] == 1
    assert result["answer"]["human_supported_rate"] == 1


def test_duplicate_cases_and_fabricated_selection_are_rejected():
    with pytest.raises(ValueError):
        evaluate([case(), case()])
    with pytest.raises(ValueError):
        evaluate([case(selected_ids=["not-retrieved"])])
