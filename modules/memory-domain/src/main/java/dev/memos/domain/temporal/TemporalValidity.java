package dev.memos.domain.temporal;

import dev.memos.domain.candidate.TemporalPrecision;
import java.time.Instant;
import java.util.Objects;

public record TemporalValidity(
    String originalText,
    Instant startInclusive,
    Instant endExclusive,
    TemporalPrecision precision,
    double confidence) {
  public static final double CERTAIN_CONFIDENCE = 0.8;

  public TemporalValidity {
    if (originalText != null) {
      originalText = TemporalValidation.text(originalText, "originalText", 256);
    }
    if (originalText == null && startInclusive == null && endExclusive == null) {
      throw new IllegalArgumentException("validity must include text or a temporal bound");
    }
    Objects.requireNonNull(precision, "precision must not be null");
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be in [0,1]");
    }
    if (startInclusive != null && endExclusive != null && !startInclusive.isBefore(endExclusive)) {
      throw new IllegalArgumentException("startInclusive must be before endExclusive");
    }
  }

  public boolean uncertain() {
    return precision == TemporalPrecision.UNKNOWN || confidence < CERTAIN_CONFIDENCE;
  }

  public TemporalRelation relationTo(TemporalValidity other) {
    Objects.requireNonNull(other, "other must not be null");
    if (uncertain() || other.uncertain()) {
      return TemporalRelation.INDETERMINATE;
    }
    if (endExclusive != null
        && other.startInclusive != null
        && !endExclusive.isAfter(other.startInclusive)) {
      return TemporalRelation.BEFORE;
    }
    if (startInclusive != null
        && other.endExclusive != null
        && !startInclusive.isBefore(other.endExclusive)) {
      return TemporalRelation.AFTER;
    }
    if (startInclusive != null
        && endExclusive != null
        && other.startInclusive != null
        && other.endExclusive != null) {
      return TemporalRelation.OVERLAPS;
    }
    return TemporalRelation.INDETERMINATE;
  }
}
