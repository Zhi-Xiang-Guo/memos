package dev.memos.domain.temporal;

public record EvidenceSpan(int startInclusive, int endExclusive) {
  public EvidenceSpan {
    if (startInclusive < 0 || endExclusive <= startInclusive) {
      throw new IllegalArgumentException("evidence span must be a positive half-open range");
    }
  }
}
