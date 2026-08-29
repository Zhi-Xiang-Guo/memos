package dev.memos.adapters.metrics;

import dev.memos.governance.DeletionTargetType;
import dev.memos.governance.DeletionWorkerTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

public final class MicrometerDeletionWorkerTelemetry implements DeletionWorkerTelemetry {
  private final MeterRegistry registry;

  public MicrometerDeletionWorkerTelemetry(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public void claimed(int count) {
    registry.counter("memos.deletion.claimed").increment(count);
  }

  @Override
  public void completed(DeletionTargetType targetType, Duration propagationDuration) {
    registry.counter("memos.deletion.completed", "target", targetType.name()).increment();
    Timer.builder("memos.deletion.propagation")
        .tag("target", targetType.name())
        .register(registry)
        .record(propagationDuration);
  }

  @Override
  public void retryScheduled(DeletionTargetType targetType) {
    registry.counter("memos.deletion.retry", "target", targetType.name()).increment();
  }

  @Override
  public void dead(DeletionTargetType targetType) {
    registry.counter("memos.deletion.dead", "target", targetType.name()).increment();
  }

  @Override
  public void leaseLost(DeletionTargetType targetType) {
    registry.counter("memos.deletion.lease_lost", "target", targetType.name()).increment();
  }

  @Override
  public void expiredDead(int count) {
    registry.counter("memos.deletion.expired_dead").increment(count);
  }
}
