package dev.memos.governance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class DeletionWorkerService {
  private final Clock clock;
  private final DeletionStore store;
  private final DeletionWorkerTelemetry telemetry;
  private final String workerId;
  private final int batchSize;
  private final Duration leaseDuration;
  private final Duration backoffBase;
  private final Duration backoffCap;

  public DeletionWorkerService(
      Clock clock,
      DeletionStore store,
      DeletionWorkerTelemetry telemetry,
      String workerId,
      int batchSize,
      Duration leaseDuration,
      Duration backoffBase,
      Duration backoffCap) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    this.workerId = requireText(workerId, "workerId");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.batchSize = batchSize;
    this.leaseDuration = positive(leaseDuration, "leaseDuration");
    this.backoffBase = positive(backoffBase, "backoffBase");
    this.backoffCap = positive(backoffCap, "backoffCap");
    if (backoffBase.compareTo(backoffCap) > 0) {
      throw new IllegalArgumentException("backoffBase must not exceed backoffCap");
    }
  }

  public DeletionRunSummary runOnce() {
    Instant now = clock.instant();
    int expiredDead = store.deadLetterExpiredExhausted(now);
    telemetry.expiredDead(expiredDead);
    List<ClaimedDeletion> deletions = store.claim(workerId, batchSize, now, leaseDuration);
    telemetry.claimed(deletions.size());
    MutableSummary summary = new MutableSummary(deletions.size(), expiredDead);
    deletions.forEach(deletion -> process(deletion, summary));
    return summary.result();
  }

  private void process(ClaimedDeletion deletion, MutableSummary summary) {
    try {
      Instant completedAt = clock.instant();
      DeletionStoreResult result = store.erase(deletion, completedAt);
      if (result == DeletionStoreResult.UPDATED) {
        summary.completed++;
        Duration propagation = Duration.between(deletion.operation().requestedAt(), completedAt);
        telemetry.completed(
            deletion.operation().targetType(),
            propagation.isNegative() ? Duration.ZERO : propagation);
      } else {
        leaseLost(deletion, summary);
      }
    } catch (RuntimeException exception) {
      String errorClass = exception.getClass().getSimpleName();
      if (errorClass.isBlank()) {
        errorClass = "UnexpectedRuntimeFailure";
      }
      if (deletion.operation().attempt() >= deletion.operation().maxAttempts()) {
        if (store.markDead(deletion, errorClass, clock.instant()) == DeletionStoreResult.UPDATED) {
          summary.dead++;
          telemetry.dead(deletion.operation().targetType());
        } else {
          leaseLost(deletion, summary);
        }
        return;
      }
      Instant next = clock.instant().plus(backoff(deletion.operation().attempt()));
      if (store.scheduleRetry(deletion, errorClass, next, clock.instant())
          == DeletionStoreResult.UPDATED) {
        summary.retries++;
        telemetry.retryScheduled(deletion.operation().targetType());
      } else {
        leaseLost(deletion, summary);
      }
    }
  }

  private Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(attempt - 1, 30);
    Duration calculated;
    try {
      calculated = backoffBase.multipliedBy(multiplier);
    } catch (ArithmeticException exception) {
      return backoffCap;
    }
    return calculated.compareTo(backoffCap) > 0 ? backoffCap : calculated;
  }

  private void leaseLost(ClaimedDeletion deletion, MutableSummary summary) {
    summary.leaseLost++;
    telemetry.leaseLost(deletion.operation().targetType());
  }

  private static Duration positive(Duration value, String field) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static final class MutableSummary {
    private final int claimed;
    private final int expiredDead;
    private int completed;
    private int retries;
    private int dead;
    private int leaseLost;

    private MutableSummary(int claimed, int expiredDead) {
      this.claimed = claimed;
      this.expiredDead = expiredDead;
    }

    private DeletionRunSummary result() {
      return new DeletionRunSummary(claimed, completed, retries, dead, leaseLost, expiredDead);
    }
  }
}
