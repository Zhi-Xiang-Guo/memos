package dev.memos.domain.candidate;

public sealed interface CandidateValue
    permits TextCandidateValue, BooleanCandidateValue, NumberCandidateValue {
  String canonicalJson();
}
