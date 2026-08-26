package dev.memos.materialization;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record ClaimRequest(
    WorkerId workerId,
    int batchSize,
    Instant now,
    Duration leaseDuration,
    Set<JobType> supportedJobTypes) {
  public ClaimRequest(WorkerId workerId, int batchSize, Instant now, Duration leaseDuration) {
    this(workerId, batchSize, now, leaseDuration, Set.of(JobType.MATERIALIZE_SOURCE));
  }

  public ClaimRequest {
    Objects.requireNonNull(workerId, "workerId must not be null");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    supportedJobTypes =
        Set.copyOf(Objects.requireNonNull(supportedJobTypes, "supportedJobTypes must not be null"));
    if (supportedJobTypes.isEmpty()) {
      throw new IllegalArgumentException("supportedJobTypes must not be empty");
    }
  }
}
