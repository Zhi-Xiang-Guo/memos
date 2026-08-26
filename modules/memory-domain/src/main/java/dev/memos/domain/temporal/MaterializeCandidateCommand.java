package dev.memos.domain.temporal;

import dev.memos.domain.candidate.CandidateValue;
import java.time.Instant;
import java.util.Objects;

public record MaterializeCandidateCommand(
    MemoryLineageIdentity lineage,
    CandidateValue value,
    String normalizedContent,
    TemporalValidity eventTime,
    TemporalValidity validTime,
    double importance,
    double confidence,
    AssertionProvenance provenance,
    TransitionContext transitionContext,
    Instant decidedAt,
    long expectedLockVersion) {
  public MaterializeCandidateCommand {
    Objects.requireNonNull(lineage, "lineage must not be null");
    Objects.requireNonNull(value, "value must not be null");
    normalizedContent = TemporalValidation.text(normalizedContent, "normalizedContent", 8_192);
    if (!Double.isFinite(importance) || importance < 0.0 || importance > 1.0) {
      throw new IllegalArgumentException("importance must be in [0,1]");
    }
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be in [0,1]");
    }
    Objects.requireNonNull(provenance, "provenance must not be null");
    Objects.requireNonNull(transitionContext, "transitionContext must not be null");
    Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion must not be negative");
    }
  }
}
