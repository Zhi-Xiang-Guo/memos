package dev.memos.domain.temporal;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryType;
import java.util.Objects;
import java.util.regex.Pattern;

public record MemoryLineageIdentity(
    MemoryLineageId lineageId,
    LineageScope scope,
    MemoryType memoryType,
    CandidateSubject subject,
    String predicate,
    PredicateCardinality cardinality) {
  private static final Pattern PREDICATE = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

  public MemoryLineageIdentity {
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(memoryType, "memoryType must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
    predicate = TemporalValidation.text(predicate, "predicate", 128);
    if (!PREDICATE.matcher(predicate).matches()) {
      throw new IllegalArgumentException("predicate must be a normalized identifier");
    }
    Objects.requireNonNull(cardinality, "cardinality must not be null");
  }
}
