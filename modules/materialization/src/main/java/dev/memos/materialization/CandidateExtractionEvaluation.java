package dev.memos.materialization;

public sealed interface CandidateExtractionEvaluation
    permits ValidExtractionEvaluation, InvalidExtractionEvaluation {}
