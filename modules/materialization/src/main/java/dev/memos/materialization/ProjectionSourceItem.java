package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProjectionSourceItem(
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
    List<UUID> sourceEventIds) {
  public ProjectionSourceItem {
    Objects.requireNonNull(memoryId, "memoryId must not be null");
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(memoryType, "memoryType must not be null");
    Objects.requireNonNull(subjectKind, "subjectKind must not be null");
    Objects.requireNonNull(predicate, "predicate must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(normalizedContent, "normalizedContent must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    sourceEventIds =
        List.copyOf(Objects.requireNonNull(sourceEventIds, "sourceEventIds must not be null"));
    if (sourceEventIds.isEmpty()) {
      throw new IllegalArgumentException("sourceEventIds must not be empty");
    }
  }
}
