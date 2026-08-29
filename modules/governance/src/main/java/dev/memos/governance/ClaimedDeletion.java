package dev.memos.governance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedDeletion(
    DeletionOperation operation,
    String workerId,
    UUID leaseToken,
    Instant leaseExpiresAt,
    String traceId) {
  public ClaimedDeletion {
    Objects.requireNonNull(operation, "operation must not be null");
    if (operation.state() != DeletionState.CLAIMED) {
      throw new IllegalArgumentException("claimed deletion must have CLAIMED state");
    }
    Objects.requireNonNull(workerId, "workerId must not be null");
    if (workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
  }
}
