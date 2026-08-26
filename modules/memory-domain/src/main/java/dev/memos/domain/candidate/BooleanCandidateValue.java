package dev.memos.domain.candidate;

public record BooleanCandidateValue(boolean value) implements CandidateValue {
  @Override
  public String canonicalJson() {
    return Boolean.toString(value);
  }
}
