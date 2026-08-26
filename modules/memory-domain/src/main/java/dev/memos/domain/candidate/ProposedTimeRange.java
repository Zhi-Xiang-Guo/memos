package dev.memos.domain.candidate;

import java.time.Instant;
import java.util.Objects;

public record ProposedTimeRange(
    String originalText,
    Instant startInclusive,
    Instant endExclusive,
    TemporalPrecision precision,
    double confidence) {
  public ProposedTimeRange {
    if (originalText != null) {
      originalText = CandidateValidation.requireText(originalText, "originalText", 256);
    }
    if (originalText == null && startInclusive == null && endExclusive == null) {
      throw new IllegalArgumentException("a proposed time range must contain text or a boundary");
    }
    Objects.requireNonNull(precision, "precision must not be null");
    confidence = CandidateValidation.requireUnitInterval(confidence, "confidence");
    if (startInclusive != null && endExclusive != null && !startInclusive.isBefore(endExclusive)) {
      throw new IllegalArgumentException("startInclusive must be before endExclusive");
    }
  }
}
