package dev.memos.materialization;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class OutboxWorkerService {
  private final Clock clock;
  private final MaterializationJobStore store;
  private final MaterializationJobHandler handler;
  private final ExponentialBackoffPolicy backoffPolicy;
  private final OutboxWorkerTelemetry telemetry;
  private final WorkerId workerId;
  private final int batchSize;
  private final Duration leaseDuration;

  public OutboxWorkerService(
      Clock clock,
      MaterializationJobStore store,
      MaterializationJobHandler handler,
      ExponentialBackoffPolicy backoffPolicy,
      OutboxWorkerTelemetry telemetry,
      WorkerId workerId,
      int batchSize,
      Duration leaseDuration) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.handler = Objects.requireNonNull(handler, "handler must not be null");
    this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy must not be null");
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.batchSize = batchSize;
    Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    this.leaseDuration = leaseDuration;
  }

  public OutboxRunSummary runOnce() {
    Instant claimTime = clock.instant();
    int expiredExhausted = store.deadLetterExpiredExhaustedJobs(claimTime);
    telemetry.expiredExhausted(expiredExhausted);
    List<ClaimedJob> jobs =
        List.copyOf(store.claim(new ClaimRequest(workerId, batchSize, claimTime, leaseDuration)));
    telemetry.claimed(jobs.size());

    MutableSummary summary = new MutableSummary(jobs.size(), expiredExhausted);
    for (ClaimedJob job : jobs) {
      process(job, summary);
    }
    return summary.toResult();
  }

  private void process(ClaimedJob job, MutableSummary summary) {
    try {
      JobHandlingResult handlingResult =
          Objects.requireNonNull(handler.handle(job), "handler result must not be null");
      if (handlingResult == JobHandlingResult.COMPLETED_ATOMICALLY) {
        summary.succeeded++;
        telemetry.succeeded(job.jobType());
        return;
      }
      if (handlingResult == JobHandlingResult.DEAD_ATOMICALLY) {
        summary.dead++;
        telemetry.dead(job.jobType());
        return;
      }
      if (handlingResult == JobHandlingResult.LEASE_LOST) {
        recordLeaseLost(job, summary);
        return;
      }
      FencedUpdateResult result = store.markSucceeded(job.fence(), clock.instant());
      if (result == FencedUpdateResult.UPDATED) {
        summary.succeeded++;
        telemetry.succeeded(job.jobType());
      } else {
        recordLeaseLost(job, summary);
      }
    } catch (JobHandlingException exception) {
      handleFailure(job, exception.kind(), exception.errorClass(), summary);
    } catch (RuntimeException exception) {
      String simpleName = exception.getClass().getSimpleName();
      JobErrorClass errorClass =
          new JobErrorClass(simpleName.isBlank() ? "UnexpectedRuntimeFailure" : simpleName);
      handleFailure(job, JobFailureKind.TRANSIENT, errorClass, summary);
    }
  }

  private void handleFailure(
      ClaimedJob job,
      JobFailureKind failureKind,
      JobErrorClass errorClass,
      MutableSummary summary) {
    Instant now = clock.instant();
    if (failureKind == JobFailureKind.PERMANENT || job.attempt() >= job.maxAttempts()) {
      FencedUpdateResult result = store.markDead(job.fence(), errorClass, now);
      if (result == FencedUpdateResult.UPDATED) {
        summary.dead++;
        telemetry.dead(job.jobType());
      } else {
        recordLeaseLost(job, summary);
      }
      return;
    }

    Instant nextAttemptAt = backoffPolicy.nextAttemptAt(now, job.attempt());
    FencedUpdateResult result = store.scheduleRetry(job.fence(), errorClass, nextAttemptAt, now);
    if (result == FencedUpdateResult.UPDATED) {
      summary.retriesScheduled++;
      telemetry.retryScheduled(job.jobType());
    } else {
      recordLeaseLost(job, summary);
    }
  }

  private void recordLeaseLost(ClaimedJob job, MutableSummary summary) {
    summary.leaseLost++;
    telemetry.leaseLost(job.jobType());
  }

  private static final class MutableSummary {
    private final int claimed;
    private final int expiredExhausted;
    private int succeeded;
    private int retriesScheduled;
    private int dead;
    private int leaseLost;

    private MutableSummary(int claimed, int expiredExhausted) {
      this.claimed = claimed;
      this.expiredExhausted = expiredExhausted;
    }

    private OutboxRunSummary toResult() {
      return new OutboxRunSummary(
          claimed, succeeded, retriesScheduled, dead, leaseLost, expiredExhausted);
    }
  }
}
