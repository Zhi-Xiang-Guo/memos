package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.memos.governance.MemoryScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxWorkerServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  @Test
  void completesSuccessfulJobWithItsLeaseFence() {
    FakeStore store = new FakeStore(claimedJob(1, 5));
    OutboxWorkerService worker = worker(store, job -> {});

    OutboxRunSummary summary = worker.runOnce();

    assertEquals(1, summary.claimed());
    assertEquals(1, summary.succeeded());
    assertEquals(0, summary.retriesScheduled());
    assertEquals(store.claimed.getFirst().fence(), store.completedFence);
  }

  @Test
  void schedulesTransientFailureUsingCurrentClaimAttempt() {
    FakeStore store = new FakeStore(claimedJob(3, 5));
    OutboxWorkerService worker =
        worker(
            store,
            job -> {
              throw JobHandlingException.transientFailure("ProviderTimeout");
            });

    OutboxRunSummary summary = worker.runOnce();

    assertEquals(1, summary.retriesScheduled());
    assertEquals(new JobErrorClass("ProviderTimeout"), store.errorClass);
    assertEquals(NOW.plusSeconds(4), store.nextAttemptAt);
  }

  @Test
  void sendsPermanentAndExhaustedFailuresToDeadState() {
    FakeStore permanentStore = new FakeStore(claimedJob(1, 5));
    FakeStore exhaustedStore = new FakeStore(claimedJob(5, 5));

    OutboxRunSummary permanent =
        worker(
                permanentStore,
                job -> {
                  throw JobHandlingException.permanentFailure("MalformedPayload");
                })
            .runOnce();
    OutboxRunSummary exhausted =
        worker(
                exhaustedStore,
                job -> {
                  throw JobHandlingException.transientFailure("ProviderTimeout");
                })
            .runOnce();

    assertEquals(1, permanent.dead());
    assertEquals(1, exhausted.dead());
    assertEquals(new JobErrorClass("MalformedPayload"), permanentStore.errorClass);
    assertEquals(new JobErrorClass("ProviderTimeout"), exhaustedStore.errorClass);
  }

  @Test
  void reportsLeaseLossWithoutOverwritingNewOwner() {
    FakeStore store = new FakeStore(claimedJob(1, 5));
    store.updateResult = FencedUpdateResult.LEASE_LOST;

    OutboxRunSummary summary = worker(store, job -> {}).runOnce();

    assertEquals(1, summary.leaseLost());
    assertEquals(0, summary.succeeded());
  }

  @Test
  void classifiesUnexpectedRuntimeFailureAsTransientWithoutLeakingMessage() {
    FakeStore store = new FakeStore(claimedJob(1, 5));

    OutboxRunSummary summary =
        worker(
                store,
                job -> {
                  throw new IllegalStateException("raw sensitive detail");
                })
            .runOnce();

    assertEquals(1, summary.retriesScheduled());
    assertEquals(new JobErrorClass("IllegalStateException"), store.errorClass);
  }

  @Test
  void valueObjectKeepsLeaseOwnerAndTokenTogether() {
    ClaimedJob job = claimedJob(1, 5);

    assertSame(job.leaseOwner(), job.fence().leaseOwner());
    assertSame(job.leaseToken(), job.fence().leaseToken());
  }

  private static OutboxWorkerService worker(FakeStore store, MaterializationJobHandler handler) {
    return new OutboxWorkerService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        store,
        handler,
        new ExponentialBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1)),
        OutboxWorkerTelemetry.NOOP,
        new WorkerId("worker-1"),
        10,
        Duration.ofSeconds(30));
  }

  private static ClaimedJob claimedJob(int attempt, int maxAttempts) {
    return new ClaimedJob(
        new JobId(new UUID(0, attempt)),
        JobType.MATERIALIZE_SOURCE,
        new MemoryScope("tenant-1", "user-1", "agent-1"),
        new UUID(1, 1),
        new SemanticJobKey("MATERIALIZE_SOURCE/source/ingestion-v1"),
        "ingestion-v1",
        "deterministic-fake-v1",
        attempt,
        maxAttempts,
        new WorkerId("worker-1"),
        new LeaseToken(new UUID(2, attempt)),
        NOW.plusSeconds(30),
        "trace-1");
  }

  private static final class FakeStore implements MaterializationJobStore {
    private final List<ClaimedJob> claimed;
    private FencedUpdateResult updateResult = FencedUpdateResult.UPDATED;
    private LeaseFence completedFence;
    private JobErrorClass errorClass;
    private Instant nextAttemptAt;

    private FakeStore(ClaimedJob claimed) {
      this.claimed = List.of(claimed);
    }

    @Override
    public int deadLetterExpiredExhaustedJobs(Instant now) {
      return 0;
    }

    @Override
    public List<ClaimedJob> claim(ClaimRequest request) {
      return claimed;
    }

    @Override
    public FencedUpdateResult markSucceeded(LeaseFence fence, Instant completedAt) {
      completedFence = fence;
      return updateResult;
    }

    @Override
    public FencedUpdateResult scheduleRetry(
        LeaseFence fence, JobErrorClass failureClass, Instant scheduledAt, Instant updatedAt) {
      errorClass = failureClass;
      nextAttemptAt = scheduledAt;
      return updateResult;
    }

    @Override
    public FencedUpdateResult markDead(
        LeaseFence fence, JobErrorClass failureClass, Instant completedAt) {
      errorClass = failureClass;
      return updateResult;
    }

    @Override
    public Optional<MaterializationJob> find(MemoryScope scope, JobId jobId) {
      return Optional.empty();
    }

    @Override
    public ReplayResult replay(MemoryScope scope, JobId jobId, Instant replayedAt) {
      return ReplayResult.NOT_FOUND;
    }
  }
}
