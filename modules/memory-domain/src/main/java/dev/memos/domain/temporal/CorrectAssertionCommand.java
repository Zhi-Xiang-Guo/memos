package dev.memos.domain.temporal;

import dev.memos.domain.candidate.CandidateValue;
import java.time.Instant;
import java.util.Objects;

public record CorrectAssertionCommand(
    MemoryLineageIdentity lineage,
    AssertionVersionId incorrectVersionId,
    CandidateValue replacementValue,
    String replacementNormalizedContent,
    TemporalValidity replacementEventTime,
    TemporalValidity replacementValidTime,
    double replacementImportance,
    double replacementConfidence,
    AssertionProvenance provenance,
    TransitionContext transitionContext,
    String reason,
    Instant correctedAt,
    long expectedLockVersion) {
  public CorrectAssertionCommand {
    Objects.requireNonNull(lineage, "lineage must not be null");
    Objects.requireNonNull(incorrectVersionId, "incorrectVersionId must not be null");
    Objects.requireNonNull(replacementValue, "replacementValue must not be null");
    replacementNormalizedContent =
        TemporalValidation.text(
            replacementNormalizedContent, "replacementNormalizedContent", 8_192);
    if (!Double.isFinite(replacementImportance)
        || replacementImportance < 0.0
        || replacementImportance > 1.0) {
      throw new IllegalArgumentException("replacementImportance must be in [0,1]");
    }
    if (!Double.isFinite(replacementConfidence)
        || replacementConfidence < 0.0
        || replacementConfidence > 1.0) {
      throw new IllegalArgumentException("replacementConfidence must be in [0,1]");
    }
    Objects.requireNonNull(provenance, "provenance must not be null");
    Objects.requireNonNull(transitionContext, "transitionContext must not be null");
    reason = TemporalValidation.text(reason, "reason", 256);
    Objects.requireNonNull(correctedAt, "correctedAt must not be null");
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion must not be negative");
    }
  }
}
