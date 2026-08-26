package dev.memos.domain.temporal;

import dev.memos.domain.candidate.CandidateValue;
import java.time.Instant;
import java.util.Objects;

public record AssertionVersion(
    AssertionVersionId versionId,
    MemoryLineageId lineageId,
    long ordinal,
    CandidateValue value,
    String normalizedContent,
    TemporalValidity eventTime,
    TemporalValidity validTime,
    double importance,
    double confidence,
    AssertionProvenance provenance,
    Instant recordedAt) {
  public AssertionVersion {
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    if (ordinal < 1) {
      throw new IllegalArgumentException("ordinal must be positive");
    }
    Objects.requireNonNull(value, "value must not be null");
    normalizedContent = TemporalValidation.text(normalizedContent, "normalizedContent", 8_192);
    if (!Double.isFinite(importance) || importance < 0.0 || importance > 1.0) {
      throw new IllegalArgumentException("importance must be in [0,1]");
    }
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be in [0,1]");
    }
    Objects.requireNonNull(provenance, "provenance must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
  }
}
