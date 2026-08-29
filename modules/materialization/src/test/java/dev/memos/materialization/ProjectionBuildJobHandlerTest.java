package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.governance.MemoryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionBuildJobHandlerTest {
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void embedsEveryRetainedVersionBeforeOneAtomicCommit() throws Exception {
    ClaimedJob job = job();
    ProjectionBuildPlan plan =
        new ProjectionBuildPlan(
            job,
            new UUID(10, 1),
            new UUID(11, 1),
            3,
            List.of(source(new UUID(12, 1)), source(new UUID(12, 2))));
    RecordingStore store = new RecordingStore(plan, ProjectionCommitResult.COMMITTED);
    ProjectionBuildJobHandler handler =
        new ProjectionBuildJobHandler(
            Clock.fixed(NOW, ZoneOffset.UTC),
            store,
            request ->
                new ProjectionEmbedding(List.of(1.0f, 0.0f), "test", request.modelVersion(), 2));

    JobHandlingResult result = handler.handle(job);

    assertEquals(JobHandlingResult.COMPLETED_ATOMICALLY, result);
    assertEquals(2, store.committed.projectedVersions().size());
    assertEquals(NOW, store.committed.committedAt());
  }

  @Test
  void supportsAnEmptyProjectionAfterAllVersionsBecomeInvalidated() throws Exception {
    ClaimedJob job = job();
    ProjectionBuildPlan plan =
        new ProjectionBuildPlan(job, new UUID(10, 1), new UUID(11, 1), 4, List.of());
    RecordingStore store = new RecordingStore(plan, ProjectionCommitResult.COMMITTED);
    ProjectionBuildJobHandler handler =
        new ProjectionBuildJobHandler(
            Clock.fixed(NOW, ZoneOffset.UTC),
            store,
            request -> {
              throw new AssertionError("embedding must not be called for an empty projection");
            });

    assertEquals(JobHandlingResult.COMPLETED_ATOMICALLY, handler.handle(job));
    assertEquals(0, store.committed.projectedVersions().size());
  }

  @Test
  void rejectsProviderModelDriftAsPermanentFailure() {
    ClaimedJob job = job();
    ProjectionBuildPlan plan =
        new ProjectionBuildPlan(
            job, new UUID(10, 1), new UUID(11, 1), 3, List.of(source(new UUID(12, 1))));
    ProjectionBuildJobHandler handler =
        new ProjectionBuildJobHandler(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new RecordingStore(plan, ProjectionCommitResult.COMMITTED),
            request -> new ProjectionEmbedding(List.of(1.0f), "test", "drifted-model", 1));

    JobHandlingException exception =
        assertThrows(JobHandlingException.class, () -> handler.handle(job));

    assertEquals(JobFailureKind.PERMANENT, exception.kind());
    assertEquals("EMBEDDING_MODEL_VERSION_MISMATCH", exception.errorClass().value());
  }

  @Test
  void preservesProviderRetryClassificationForWorkerStateTransitions() {
    assertProviderFailure(
        ProjectionEmbeddingProviderException.transientFailure(
            "OLLAMA_EMBEDDING_TIMEOUT", new RuntimeException("provider detail")),
        JobFailureKind.TRANSIENT,
        "OLLAMA_EMBEDDING_TIMEOUT");
    assertProviderFailure(
        ProjectionEmbeddingProviderException.permanentFailure(
            "OLLAMA_EMBEDDING_MODEL_DRIFT", new RuntimeException("provider detail")),
        JobFailureKind.PERMANENT,
        "OLLAMA_EMBEDDING_MODEL_DRIFT");
  }

  private static void assertProviderFailure(
      ProjectionEmbeddingProviderException providerFailure,
      JobFailureKind expectedKind,
      String expectedErrorClass) {
    ClaimedJob job = job();
    ProjectionBuildPlan plan =
        new ProjectionBuildPlan(
            job, new UUID(10, 1), new UUID(11, 1), 3, List.of(source(new UUID(12, 1))));
    ProjectionBuildJobHandler handler =
        new ProjectionBuildJobHandler(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new RecordingStore(plan, ProjectionCommitResult.COMMITTED),
            request -> {
              throw providerFailure;
            });

    JobHandlingException exception =
        assertThrows(JobHandlingException.class, () -> handler.handle(job));

    assertEquals(expectedKind, exception.kind());
    assertEquals(expectedErrorClass, exception.errorClass().value());
  }

  private static ProjectionSourceItem source(UUID versionId) {
    return new ProjectionSourceItem(
        new UUID(10, 1),
        versionId,
        MemoryType.SEMANTIC,
        SubjectKind.USER,
        null,
        "preference.editor.theme",
        AssertionStatus.CURRENT,
        "The user prefers a dark editor theme.",
        null,
        null,
        NOW.minusSeconds(10),
        List.of(new UUID(13, versionId.getLeastSignificantBits())));
  }

  private static ClaimedJob job() {
    return new ClaimedJob(
        new JobId(new UUID(1, 1)),
        JobType.PROJECTION_BUILD,
        new MemoryScope("tenant-a", "user-a", "agent-a"),
        new UUID(2, 1),
        new SemanticJobKey("projection/test"),
        "projection-v1",
        "embedding-v1",
        1,
        3,
        new WorkerId("worker-a"),
        new LeaseToken(new UUID(3, 1)),
        NOW.plusSeconds(30),
        "trace-a");
  }

  private static final class RecordingStore implements ProjectionBuildStore {
    private final ProjectionBuildPlan plan;
    private final ProjectionCommitResult result;
    private CommitProjectionBuild committed;

    private RecordingStore(ProjectionBuildPlan plan, ProjectionCommitResult result) {
      this.plan = plan;
      this.result = result;
    }

    @Override
    public Optional<ProjectionBuildPlan> load(ClaimedJob job) {
      return Optional.of(plan);
    }

    @Override
    public ProjectionCommitResult commit(CommitProjectionBuild command) {
      committed = command;
      return result;
    }
  }
}
