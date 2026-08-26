package dev.memos.materialization;

import dev.memos.governance.MemoryScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MaterializationJob(
    JobId jobId,
    JobType jobType,
    MemoryScope scope,
    UUID sourceEventId,
    SemanticJobKey semanticJobKey,
    String policyVersion,
    String modelVersion,
    JobState state,
    int attempt,
    int maxAttempts,
    Instant nextAttemptAt,
    WorkerId leaseOwner,
    LeaseToken leaseToken,
    Instant leaseExpiresAt,
    JobErrorClass errorClass,
    int replayCount,
    String traceId,
    Instant createdAt,
    Instant updatedAt) {
  public MaterializationJob {
    Objects.requireNonNull(jobId, "jobId must not be null");
    Objects.requireNonNull(jobType, "jobType must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    Objects.requireNonNull(semanticJobKey, "semanticJobKey must not be null");
    policyVersion = MaterializationTextValidation.requireText(policyVersion, "policyVersion", 128);
    modelVersion = MaterializationTextValidation.requireText(modelVersion, "modelVersion", 128);
    Objects.requireNonNull(state, "state must not be null");
    if (attempt < 0 || maxAttempts < 1 || attempt > maxAttempts) {
      throw new IllegalArgumentException("attempt must be between 0 and maxAttempts");
    }
    if ((state == JobState.PENDING || state == JobState.RETRY_WAIT) && nextAttemptAt == null) {
      throw new IllegalArgumentException("ready jobs require nextAttemptAt");
    }
    boolean hasCompleteLease = leaseOwner != null && leaseToken != null && leaseExpiresAt != null;
    boolean hasAnyLease = leaseOwner != null || leaseToken != null || leaseExpiresAt != null;
    if (state == JobState.CLAIMED && !hasCompleteLease) {
      throw new IllegalArgumentException("CLAIMED jobs require a complete lease");
    }
    if (state != JobState.CLAIMED && hasAnyLease) {
      throw new IllegalArgumentException("only CLAIMED jobs may carry a lease");
    }
    if (replayCount < 0) {
      throw new IllegalArgumentException("replayCount must not be negative");
    }
    traceId = MaterializationTextValidation.requireText(traceId, "traceId", 128);
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }
}
