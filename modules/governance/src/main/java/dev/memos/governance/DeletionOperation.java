package dev.memos.governance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeletionOperation(
    UUID operationId,
    String tenantId,
    String requesterSubjectId,
    DeletionTargetType targetType,
    UUID targetMemoryId,
    String targetUserId,
    DeletionPolicyBasis policyBasis,
    DeletionState state,
    DeletionStepState sourceState,
    DeletionStepState authorityState,
    DeletionStepState projectionState,
    DeletionStepState jobState,
    int attempt,
    int maxAttempts,
    Instant nextAttemptAt,
    String errorClass,
    Instant requestedAt,
    Instant completedAt) {
  public DeletionOperation {
    Objects.requireNonNull(operationId, "operationId must not be null");
    requireText(tenantId, "tenantId");
    requireText(requesterSubjectId, "requesterSubjectId");
    Objects.requireNonNull(targetType, "targetType must not be null");
    Objects.requireNonNull(policyBasis, "policyBasis must not be null");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(sourceState, "sourceState must not be null");
    Objects.requireNonNull(authorityState, "authorityState must not be null");
    Objects.requireNonNull(projectionState, "projectionState must not be null");
    Objects.requireNonNull(jobState, "jobState must not be null");
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    if (attempt < 0 || maxAttempts < 1 || attempt > maxAttempts) {
      throw new IllegalArgumentException("deletion attempt counters are invalid");
    }
    if ((targetType == DeletionTargetType.MEMORY) != (targetMemoryId != null)
        || (targetType == DeletionTargetType.USER) != (targetUserId != null)) {
      throw new IllegalArgumentException("deletion target does not match target type");
    }
  }

  private static void requireText(String value, String field) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
