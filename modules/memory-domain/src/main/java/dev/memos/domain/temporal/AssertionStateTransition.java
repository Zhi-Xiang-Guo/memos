package dev.memos.domain.temporal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AssertionStateTransition(
    StateTransitionId transitionId,
    MemoryLineageId lineageId,
    long sequence,
    TransitionOperation operation,
    UUID causedByCandidateId,
    List<AssertionVersionId> relatedVersions,
    List<StatusChange> statusChanges,
    TransitionContext context,
    String reason,
    Instant occurredAt) {
  public AssertionStateTransition {
    Objects.requireNonNull(transitionId, "transitionId must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(operation, "operation must not be null");
    relatedVersions = List.copyOf(Objects.requireNonNull(relatedVersions, "relatedVersions"));
    statusChanges = List.copyOf(Objects.requireNonNull(statusChanges, "statusChanges"));
    if (relatedVersions.isEmpty()) {
      throw new IllegalArgumentException("relatedVersions must not be empty");
    }
    if (relatedVersions.stream().distinct().count() != relatedVersions.size()) {
      throw new IllegalArgumentException("relatedVersions must be unique");
    }
    if (statusChanges.stream().map(StatusChange::versionId).distinct().count()
        != statusChanges.size()) {
      throw new IllegalArgumentException("statusChanges must have unique version ids");
    }
    Objects.requireNonNull(context, "context must not be null");
    reason = TemporalValidation.text(reason, "reason", 256);
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (operation == TransitionOperation.IGNORE) {
      throw new IllegalArgumentException("ignored decisions are not appended transitions");
    }
  }
}
