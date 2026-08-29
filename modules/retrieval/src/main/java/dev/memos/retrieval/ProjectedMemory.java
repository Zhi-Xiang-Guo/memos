package dev.memos.retrieval;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProjectedMemory(
    UUID memoryId,
    UUID versionId,
    MemoryType memoryType,
    SubjectKind subjectKind,
    String subjectLabel,
    String predicate,
    AssertionStatus status,
    String normalizedContent,
    Instant validFrom,
    Instant validTo,
    Instant recordedAt,
    List<UUID> sourceEventIds,
    String projectionPolicyVersion,
    String embeddingModelVersion,
    long transitionSequence,
    Instant projectedAt) {
  public ProjectedMemory {
    Objects.requireNonNull(memoryId, "memoryId must not be null");
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(memoryType, "memoryType must not be null");
    Objects.requireNonNull(subjectKind, "subjectKind must not be null");
    predicate = required(predicate, "predicate", 128);
    Objects.requireNonNull(status, "status must not be null");
    normalizedContent = required(normalizedContent, "normalizedContent", 8_192);
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    sourceEventIds =
        List.copyOf(Objects.requireNonNull(sourceEventIds, "sourceEventIds must not be null"));
    if (sourceEventIds.isEmpty()) {
      throw new IllegalArgumentException("sourceEventIds must not be empty");
    }
    projectionPolicyVersion = required(projectionPolicyVersion, "projectionPolicyVersion", 128);
    embeddingModelVersion = required(embeddingModelVersion, "embeddingModelVersion", 128);
    if (transitionSequence < 1) {
      throw new IllegalArgumentException("transitionSequence must be positive");
    }
    Objects.requireNonNull(projectedAt, "projectedAt must not be null");
  }

  private static String required(String value, String name, int max) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(name + " must contain 1 to " + max + " characters");
    }
    return value;
  }
}
