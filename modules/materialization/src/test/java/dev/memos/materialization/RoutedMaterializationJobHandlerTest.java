package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.memos.governance.MemoryScope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutedMaterializationJobHandlerTest {
  @Test
  void routesByPersistedJobType() throws Exception {
    RoutedMaterializationJobHandler handler =
        new RoutedMaterializationJobHandler(
            Map.of(
                JobType.MATERIALIZE_SOURCE,
                ignored -> JobHandlingResult.COMPLETED_ATOMICALLY,
                JobType.CANDIDATE_MATERIALIZATION,
                ignored -> JobHandlingResult.DEAD_ATOMICALLY));

    assertEquals(2, handler.supportedJobTypes().size());
    assertTrue(handler.supportedJobTypes().contains(JobType.MATERIALIZE_SOURCE));
    assertTrue(handler.supportedJobTypes().contains(JobType.CANDIDATE_MATERIALIZATION));
    assertEquals(
        JobHandlingResult.COMPLETED_ATOMICALLY, handler.handle(job(JobType.MATERIALIZE_SOURCE)));
    assertEquals(
        JobHandlingResult.DEAD_ATOMICALLY, handler.handle(job(JobType.CANDIDATE_MATERIALIZATION)));
  }

  @Test
  void rejectsMissingRouteAsPermanentFailure() {
    RoutedMaterializationJobHandler handler =
        new RoutedMaterializationJobHandler(
            Map.of(JobType.MATERIALIZE_SOURCE, ignored -> JobHandlingResult.COMPLETED_ATOMICALLY));

    JobHandlingException failure =
        assertThrows(
            JobHandlingException.class,
            () -> handler.handle(job(JobType.CANDIDATE_MATERIALIZATION)));
    assertEquals(JobFailureKind.PERMANENT, failure.kind());
  }

  private static ClaimedJob job(JobType type) {
    return new ClaimedJob(
        new JobId(UUID.fromString("00000000-0000-0000-0000-000000000010")),
        type,
        new MemoryScope("tenant", "user", "agent"),
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        new SemanticJobKey("job/key"),
        "policy-v1",
        "model-v1",
        1,
        5,
        new WorkerId("worker"),
        new LeaseToken(UUID.fromString("00000000-0000-0000-0000-000000000012")),
        Instant.parse("2026-08-27T00:01:00Z"),
        "trace");
  }
}
