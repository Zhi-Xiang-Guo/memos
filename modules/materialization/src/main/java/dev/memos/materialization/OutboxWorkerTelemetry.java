package dev.memos.materialization;

public interface OutboxWorkerTelemetry {
  OutboxWorkerTelemetry NOOP = new OutboxWorkerTelemetry() {};

  default void claimed(int count) {}

  default void succeeded(JobType jobType) {}

  default void retryScheduled(JobType jobType) {}

  default void dead(JobType jobType) {}

  default void leaseLost(JobType jobType) {}

  default void leaseRenewed(JobType jobType) {}

  default void leaseRenewalLost(JobType jobType) {}

  default void leaseRenewalFailed(JobType jobType) {}

  default void expiredExhausted(int count) {}
}
