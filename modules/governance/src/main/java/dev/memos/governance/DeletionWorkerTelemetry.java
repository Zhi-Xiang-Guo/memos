package dev.memos.governance;

import java.time.Duration;

public interface DeletionWorkerTelemetry {
  DeletionWorkerTelemetry NOOP = new DeletionWorkerTelemetry() {};

  default void claimed(int count) {}

  default void completed(DeletionTargetType targetType, Duration propagationDuration) {}

  default void retryScheduled(DeletionTargetType targetType) {}

  default void dead(DeletionTargetType targetType) {}

  default void leaseLost(DeletionTargetType targetType) {}

  default void expiredDead(int count) {}
}
