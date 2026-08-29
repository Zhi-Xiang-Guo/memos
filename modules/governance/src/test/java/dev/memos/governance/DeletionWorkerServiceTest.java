package dev.memos.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeletionWorkerServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void completesClaimedDeletionAndRecordsPropagation() {
    FakeStore store = new FakeStore(claimed(1, 5));
    RecordingTelemetry telemetry = new RecordingTelemetry();

    DeletionRunSummary summary = worker(store, telemetry).runOnce();

    assertEquals(new DeletionRunSummary(1, 1, 0, 0, 0, 0), summary);
    assertEquals(store.claimed.getFirst(), store.erased);
    assertEquals(DeletionTargetType.MEMORY, telemetry.completedType);
    assertEquals(Duration.ofHours(1), telemetry.propagation);
  }

  @Test
  void schedulesUnexpectedFailureWithBoundedExponentialBackoffWithoutLeakingMessage() {
    FakeStore store = new FakeStore(claimed(3, 5));
    store.eraseFailure = new IllegalStateException("raw deleted memory content");

    DeletionRunSummary summary = worker(store, new RecordingTelemetry()).runOnce();

    assertEquals(new DeletionRunSummary(1, 0, 1, 0, 0, 0), summary);
    assertEquals("IllegalStateException", store.errorClass);
    assertEquals(NOW.plusSeconds(4), store.nextAttemptAt);
  }

  @Test
  void sendsExhaustedFailureToDeadState() {
    FakeStore store = new FakeStore(claimed(5, 5));
    store.eraseFailure = new IllegalArgumentException("sensitive detail");

    DeletionRunSummary summary = worker(store, new RecordingTelemetry()).runOnce();

    assertEquals(new DeletionRunSummary(1, 0, 0, 1, 0, 0), summary);
    assertEquals("IllegalArgumentException", store.errorClass);
    assertNull(store.nextAttemptAt);
  }

  @Test
  void reportsLeaseLossWithoutOverwritingTheNewOwner() {
    FakeStore store = new FakeStore(claimed(1, 5));
    store.updateResult = DeletionStoreResult.LEASE_LOST;

    DeletionRunSummary summary = worker(store, new RecordingTelemetry()).runOnce();

    assertEquals(new DeletionRunSummary(1, 0, 0, 0, 1, 0), summary);
  }

  @Test
  void includesExpiredExhaustedClaimsInRunSummary() {
    FakeStore store = new FakeStore(null);
    store.expiredDead = 2;

    DeletionRunSummary summary = worker(store, new RecordingTelemetry()).runOnce();

    assertEquals(new DeletionRunSummary(0, 0, 0, 0, 0, 2), summary);
  }

  private static DeletionWorkerService worker(FakeStore store, DeletionWorkerTelemetry telemetry) {
    return new DeletionWorkerService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        store,
        telemetry,
        "deletion-worker-1",
        10,
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofSeconds(4));
  }

  private static ClaimedDeletion claimed(int attempt, int maxAttempts) {
    UUID operationId = new UUID(0, attempt);
    DeletionOperation operation =
        new DeletionOperation(
            operationId,
            "tenant-a",
            "subject-a",
            DeletionTargetType.MEMORY,
            new UUID(1, 1),
            null,
            DeletionPolicyBasis.USER_REQUEST,
            DeletionState.CLAIMED,
            DeletionStepState.NOT_APPLICABLE,
            DeletionStepState.PENDING,
            DeletionStepState.COMPLETED,
            DeletionStepState.COMPLETED,
            attempt,
            maxAttempts,
            null,
            null,
            NOW.minusSeconds(3600),
            null);
    return new ClaimedDeletion(
        operation, "deletion-worker-1", new UUID(2, attempt), NOW.plusSeconds(30), "trace-a");
  }

  private static final class FakeStore implements DeletionStore {
    private final List<ClaimedDeletion> claimed;
    private RuntimeException eraseFailure;
    private DeletionStoreResult updateResult = DeletionStoreResult.UPDATED;
    private ClaimedDeletion erased;
    private String errorClass;
    private Instant nextAttemptAt;
    private int expiredDead;

    private FakeStore(ClaimedDeletion claimed) {
      this.claimed = claimed == null ? List.of() : List.of(claimed);
    }

    @Override
    public DeletionRequestResult request(DeletionRequest request, int maxAttempts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<DeletionOperation> find(
        String tenantId, String requesterSubjectId, UUID operationId) {
      return Optional.empty();
    }

    @Override
    public Optional<DeletionOperation> findForTenant(String tenantId, UUID operationId) {
      return Optional.empty();
    }

    @Override
    public Optional<DeletionOperation> requeue(DeletionRequeueCommand command, int maxAttempts) {
      return Optional.empty();
    }

    @Override
    public int deadLetterExpiredExhausted(Instant now) {
      return expiredDead;
    }

    @Override
    public List<ClaimedDeletion> claim(
        String workerId, int batchSize, Instant now, Duration leaseDuration) {
      return claimed;
    }

    @Override
    public DeletionStoreResult erase(ClaimedDeletion deletion, Instant completedAt) {
      erased = deletion;
      if (eraseFailure != null) {
        throw eraseFailure;
      }
      return updateResult;
    }

    @Override
    public DeletionStoreResult scheduleRetry(
        ClaimedDeletion deletion, String failureClass, Instant scheduledAt, Instant updatedAt) {
      errorClass = failureClass;
      nextAttemptAt = scheduledAt;
      return updateResult;
    }

    @Override
    public DeletionStoreResult markDead(
        ClaimedDeletion deletion, String failureClass, Instant completedAt) {
      errorClass = failureClass;
      return updateResult;
    }
  }

  private static final class RecordingTelemetry implements DeletionWorkerTelemetry {
    private DeletionTargetType completedType;
    private Duration propagation;

    @Override
    public void completed(DeletionTargetType targetType, Duration propagationDuration) {
      completedType = targetType;
      propagation = propagationDuration;
    }
  }
}
