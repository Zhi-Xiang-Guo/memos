package dev.memos.adapters.metrics;

import dev.memos.materialization.JobType;
import dev.memos.materialization.OutboxWorkerTelemetry;
import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerOutboxWorkerTelemetry implements OutboxWorkerTelemetry {
  private final MeterRegistry registry;

  public MicrometerOutboxWorkerTelemetry(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void claimed(int count) {
    registry.counter("memos.outbox.claimed").increment(count);
  }

  @Override
  public void succeeded(JobType jobType) {
    counter("succeeded", jobType);
  }

  @Override
  public void retryScheduled(JobType jobType) {
    counter("retry", jobType);
  }

  @Override
  public void dead(JobType jobType) {
    counter("dead", jobType);
  }

  @Override
  public void leaseLost(JobType jobType) {
    counter("lease_lost", jobType);
  }

  @Override
  public void expiredExhausted(int count) {
    registry.counter("memos.outbox.expired_exhausted").increment(count);
  }

  private void counter(String outcome, JobType jobType) {
    registry
        .counter("memos.outbox.jobs", "outcome", outcome, "job.type", jobType.name())
        .increment();
  }
}
