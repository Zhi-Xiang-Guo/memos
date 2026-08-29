package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.governance.MemoryScope;
import dev.memos.materialization.ClaimRequest;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.FencedUpdateResult;
import dev.memos.materialization.JobErrorClass;
import dev.memos.materialization.JobId;
import dev.memos.materialization.JobState;
import dev.memos.materialization.JobType;
import dev.memos.materialization.LeaseFence;
import dev.memos.materialization.MaterializationJob;
import dev.memos.materialization.MaterializationJobStore;
import dev.memos.materialization.ReplayResult;
import dev.memos.materialization.SemanticJobKey;
import dev.memos.materialization.SourceMaterialization;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SourceMaterializationControllerTest {
  private static final UUID SOURCE_ID = new UUID(10, 1);
  private static final MemoryScope SCOPE = new MemoryScope("tenant-a", "user-a", "agent-a");
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void returnsScopeSafeAggregateAndContentFreeJobDiagnostics() {
    AtomicReference<MemoryScope> observedScope = new AtomicReference<>();
    var materialization =
        new SourceMaterialization(
            SOURCE_ID,
            List.of(
                job(JobType.PROJECTION_BUILD, JobState.PENDING, 3),
                job(JobType.MATERIALIZE_SOURCE, JobState.SUCCEEDED, 1)));
    var controller =
        new SourceMaterializationController(
            new ReadStore(observedScope, Optional.of(materialization)), ignored -> SCOPE);

    SourceMaterializationResponse response =
        controller.find(SOURCE_ID, new MockHttpServletRequest());

    assertThat(observedScope.get()).isEqualTo(SCOPE);
    assertThat(response.sourceEventId()).isEqualTo(SOURCE_ID.toString());
    assertThat(response.status()).isEqualTo("PROCESSING");
    assertThat(response.settledAt()).isNull();
    assertThat(response.jobs())
        .extracting(MaterializationJobResponse::jobType)
        .containsExactly("MATERIALIZE_SOURCE", "PROJECTION_BUILD");
    assertThat(response.jobs().getFirst().completedAt()).isEqualTo(NOW.plusSeconds(2));
    assertThat(response.jobs().getLast().errorClass()).isNull();
  }

  @Test
  void hidesMissingOrCrossScopeSourcesBehindTheSameNotFoundContract() {
    var controller =
        new SourceMaterializationController(
            new ReadStore(new AtomicReference<>(), Optional.empty()), ignored -> SCOPE);

    assertThatThrownBy(() -> controller.find(SOURCE_ID, new MockHttpServletRequest()))
        .isInstanceOf(SourceEventNotFoundException.class);
  }

  private static MaterializationJob job(JobType type, JobState state, int sequence) {
    Instant createdAt = NOW.plusSeconds(sequence);
    boolean terminal = state == JobState.SUCCEEDED || state == JobState.DEAD;
    return new MaterializationJob(
        new JobId(new UUID(0, sequence)),
        type,
        SCOPE,
        SOURCE_ID,
        new SemanticJobKey(type + "/" + sequence),
        "policy-v1",
        "model-v1",
        state,
        terminal ? 1 : 0,
        5,
        state == JobState.PENDING ? createdAt : null,
        null,
        null,
        null,
        state == JobState.DEAD ? new JobErrorClass("PERMANENT_FAILURE") : null,
        0,
        "trace-1",
        terminal ? createdAt.plusSeconds(1) : null,
        createdAt,
        createdAt);
  }

  private record ReadStore(
      AtomicReference<MemoryScope> observedScope, Optional<SourceMaterialization> materialization)
      implements MaterializationJobStore {
    @Override
    public int deadLetterExpiredExhaustedJobs(Instant now) {
      return 0;
    }

    @Override
    public List<ClaimedJob> claim(ClaimRequest request) {
      return List.of();
    }

    @Override
    public FencedUpdateResult markSucceeded(LeaseFence fence, Instant completedAt) {
      return FencedUpdateResult.LEASE_LOST;
    }

    @Override
    public FencedUpdateResult scheduleRetry(
        LeaseFence fence, JobErrorClass errorClass, Instant nextAttemptAt, Instant updatedAt) {
      return FencedUpdateResult.LEASE_LOST;
    }

    @Override
    public FencedUpdateResult markDead(
        LeaseFence fence, JobErrorClass errorClass, Instant completedAt) {
      return FencedUpdateResult.LEASE_LOST;
    }

    @Override
    public Optional<MaterializationJob> find(MemoryScope scope, JobId jobId) {
      return Optional.empty();
    }

    @Override
    public Optional<SourceMaterialization> findBySource(MemoryScope scope, UUID sourceEventId) {
      observedScope.set(scope);
      return materialization.filter(value -> value.sourceEventId().equals(sourceEventId));
    }

    @Override
    public ReplayResult replay(MemoryScope scope, JobId jobId, Instant replayedAt) {
      return ReplayResult.NOT_FOUND;
    }
  }
}
