package dev.memos.materialization;

import dev.memos.governance.MemoryScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedJob(
    JobId jobId,
    JobType jobType,
    MemoryScope scope,
    UUID sourceEventId,
    SemanticJobKey semanticJobKey,
    String policyVersion,
    String modelVersion,
    int attempt,
    int maxAttempts,
    WorkerId leaseOwner,
    LeaseToken leaseToken,
    Instant leaseExpiresAt,
    String traceId) {
  public ClaimedJob {
    Objects.requireNonNull(jobId, "jobId must not be null");
    Objects.requireNonNull(jobType, "jobType must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    Objects.requireNonNull(semanticJobKey, "semanticJobKey must not be null");
    policyVersion = MaterializationTextValidation.requireText(policyVersion, "policyVersion", 128);
    modelVersion = MaterializationTextValidation.requireText(modelVersion, "modelVersion", 128);
    if (attempt < 1 || maxAttempts < 1 || attempt > maxAttempts) {
      throw new IllegalArgumentException("attempt must be between 1 and maxAttempts");
    }
    Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    traceId = MaterializationTextValidation.requireText(traceId, "traceId", 128);
  }

  public LeaseFence fence() {
    return new LeaseFence(jobId, leaseOwner, leaseToken);
  }
}
