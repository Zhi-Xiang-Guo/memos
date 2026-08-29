package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.memos.governance.MemoryScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceMaterializationTest {
  private static final UUID SOURCE_ID = new UUID(10, 1);
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void ordersThePipelineAndReportsProcessingUntilEveryJobSucceeds() {
    var projection = job(JobType.PROJECTION_BUILD, JobState.PENDING, 3);
    var source = job(JobType.MATERIALIZE_SOURCE, JobState.SUCCEEDED, 1);
    var candidate = job(JobType.CANDIDATE_MATERIALIZATION, JobState.SUCCEEDED, 2);

    var materialization =
        new SourceMaterialization(SOURCE_ID, List.of(projection, source, candidate));

    assertEquals(SourceMaterializationState.PROCESSING, materialization.state());
    assertEquals(
        List.of(
            JobType.MATERIALIZE_SOURCE,
            JobType.CANDIDATE_MATERIALIZATION,
            JobType.PROJECTION_BUILD),
        materialization.jobs().stream().map(MaterializationJob::jobType).toList());
    assertEquals(NOW.plusSeconds(1), materialization.createdAt());
    assertEquals(NOW.plusSeconds(3), materialization.updatedAt());
    assertNull(materialization.settledAt());
  }

  @Test
  void deadJobMakesTheWholeSourceFailedAndPreservesTheLastCompletionTime() {
    var source = job(JobType.MATERIALIZE_SOURCE, JobState.SUCCEEDED, 1);
    var dead = job(JobType.CANDIDATE_MATERIALIZATION, JobState.DEAD, 2);

    var materialization = new SourceMaterialization(SOURCE_ID, List.of(dead, source));

    assertEquals(SourceMaterializationState.FAILED, materialization.state());
    assertEquals(NOW.plusSeconds(3), materialization.settledAt());
  }

  @Test
  void reportsSuccessOnlyWhenEveryObservedJobSucceeded() {
    var source = job(JobType.MATERIALIZE_SOURCE, JobState.SUCCEEDED, 1);
    var projection = job(JobType.PROJECTION_BUILD, JobState.SUCCEEDED, 3);

    var materialization = new SourceMaterialization(SOURCE_ID, List.of(projection, source));

    assertEquals(SourceMaterializationState.SUCCEEDED, materialization.state());
    assertEquals(NOW.plusSeconds(4), materialization.settledAt());
  }

  @Test
  void rejectsJobsFromAnotherSource() {
    var other = job(JobType.MATERIALIZE_SOURCE, JobState.SUCCEEDED, 1, new UUID(20, 1));

    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SourceMaterialization(SOURCE_ID, List.of(other)));

    assertTrue(exception.getMessage().contains("source event"));
  }

  @Test
  void rejectsNegativeProviderUsage() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SourceMaterializationUsage(true, 0, 0, -1, 1));

    assertTrue(exception.getMessage().contains("usage"));
  }

  private static MaterializationJob job(JobType type, JobState state, int sequence) {
    return job(type, state, sequence, SOURCE_ID);
  }

  private static MaterializationJob job(
      JobType type, JobState state, int sequence, UUID sourceEventId) {
    Instant createdAt = NOW.plusSeconds(sequence);
    boolean terminal = state == JobState.SUCCEEDED || state == JobState.DEAD;
    return new MaterializationJob(
        new JobId(new UUID(0, sequence)),
        type,
        new MemoryScope("tenant-a", "user-a", "agent-a"),
        sourceEventId,
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
}
