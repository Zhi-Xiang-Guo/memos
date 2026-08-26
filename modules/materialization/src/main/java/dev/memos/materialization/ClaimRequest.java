package dev.memos.materialization;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ClaimRequest(WorkerId workerId, int batchSize, Instant now, Duration leaseDuration) {
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
  }
}
