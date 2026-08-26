package dev.memos.materialization;

import java.util.Objects;

public record LeaseFence(JobId jobId, WorkerId leaseOwner, LeaseToken leaseToken) {
  public LeaseFence {
    Objects.requireNonNull(jobId, "jobId must not be null");
    Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
  }
}
