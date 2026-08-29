package dev.memos.adapters.system;

import dev.memos.materialization.FencedUpdateResult;
import dev.memos.materialization.JobLeaseHeartbeat;
import dev.memos.materialization.JobLeaseHeartbeatSession;
import dev.memos.materialization.JobType;
import dev.memos.materialization.LeaseFence;
import dev.memos.materialization.MaterializationJobStore;
import dev.memos.materialization.OutboxWorkerTelemetry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Process-local scheduler for PostgreSQL-time, lease-token-fenced job renewals. */
public final class ScheduledJobLeaseHeartbeat implements JobLeaseHeartbeat, AutoCloseable {
  private final MaterializationJobStore store;
  private final OutboxWorkerTelemetry telemetry;
  private final ScheduledExecutorService scheduler;

  public ScheduledJobLeaseHeartbeat(
      MaterializationJobStore store, OutboxWorkerTelemetry telemetry) {
    this(
        store,
        telemetry,
        Executors.newScheduledThreadPool(
            1, Thread.ofPlatform().daemon(true).name("memos-job-lease-heartbeat-", 0).factory()));
  }

  ScheduledJobLeaseHeartbeat(
      MaterializationJobStore store,
      OutboxWorkerTelemetry telemetry,
      ScheduledExecutorService scheduler) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
  }

  @Override
  public JobLeaseHeartbeatSession start(LeaseFence fence, JobType jobType, Duration leaseDuration) {
    Objects.requireNonNull(fence, "fence must not be null");
    Objects.requireNonNull(jobType, "jobType must not be null");
    long leaseMilliseconds = positiveMilliseconds(leaseDuration);
    long intervalMilliseconds = Math.max(1, leaseMilliseconds / 3);
    Session session = new Session(fence, jobType, leaseDuration);
    ScheduledFuture<?> future =
        scheduler.scheduleWithFixedDelay(
            session::renew, intervalMilliseconds, intervalMilliseconds, TimeUnit.MILLISECONDS);
    session.attach(future);
    return session;
  }

  @Override
  public void close() {
    scheduler.close();
  }

  private static long positiveMilliseconds(Duration duration) {
    Objects.requireNonNull(duration, "leaseDuration must not be null");
    long milliseconds = duration.toMillis();
    if (milliseconds < 1) {
      throw new IllegalArgumentException("leaseDuration must be at least one millisecond");
    }
    return milliseconds;
  }

  private final class Session implements JobLeaseHeartbeatSession {
    private final LeaseFence fence;
    private final JobType jobType;
    private final Duration leaseDuration;
    private final ReentrantLock execution = new ReentrantLock();
    private ScheduledFuture<?> future;
    private boolean closed;
    private boolean leaseLost;

    private Session(LeaseFence fence, JobType jobType, Duration leaseDuration) {
      this.fence = fence;
      this.jobType = jobType;
      this.leaseDuration = leaseDuration;
    }

    private void attach(ScheduledFuture<?> scheduled) {
      execution.lock();
      try {
        future = scheduled;
        if (closed) {
          scheduled.cancel(false);
        }
      } finally {
        execution.unlock();
      }
    }

    private void renew() {
      execution.lock();
      try {
        if (closed || leaseLost) {
          return;
        }
        try {
          FencedUpdateResult result = store.renewLease(fence, leaseDuration);
          if (result == FencedUpdateResult.UPDATED) {
            telemetry.leaseRenewed(jobType);
          } else {
            leaseLost = true;
            telemetry.leaseRenewalLost(jobType);
          }
        } catch (RuntimeException exception) {
          telemetry.leaseRenewalFailed(jobType);
        }
      } finally {
        execution.unlock();
      }
    }

    @Override
    public boolean leaseLost() {
      execution.lock();
      try {
        return leaseLost;
      } finally {
        execution.unlock();
      }
    }

    @Override
    public void close() {
      execution.lock();
      try {
        closed = true;
        if (future != null) {
          future.cancel(false);
        }
      } finally {
        execution.unlock();
      }
    }
  }
}
