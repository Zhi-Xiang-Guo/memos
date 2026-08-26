package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.TransitionOperation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalMemoryMutationContractTest {
  private static final LineageScope SCOPE = new LineageScope("tenant-a", "user-a", "agent-a");
  private static final MemoryLineageId LINEAGE_ID = new MemoryLineageId(uuid(1));
  private static final AssertionVersionId VERSION_ID = new AssertionVersionId(uuid(2));

  @Test
  void selectionsCarryOnlyOpaqueExistingEvidenceAndConcurrencyMetadata() {
    CorrectionSelection correction =
        new CorrectionSelection(
            SCOPE,
            LINEAGE_ID,
            VERSION_ID,
            uuid(3),
            uuid(4),
            "correction-key",
            7,
            "USER_CORRECTION",
            "trace-1",
            Instant.parse("2026-08-27T00:00:00Z"));
    InvalidationSelection invalidation =
        new InvalidationSelection(
            SCOPE,
            LINEAGE_ID,
            VERSION_ID,
            uuid(5),
            "invalidation-key",
            8,
            "USER_DISPUTED",
            "trace-2",
            Instant.parse("2026-08-27T00:01:00Z"));

    assertEquals(uuid(4), correction.candidateId());
    assertEquals(7, correction.expectedLockVersion());
    assertEquals(uuid(5), invalidation.sourceEventId());
    assertEquals("trace-2", invalidation.traceId());
  }

  @Test
  void resultDefensivelyCopiesContentFreeIdentifiers() {
    List<AssertionVersionId> versions = new ArrayList<>(List.of(VERSION_ID));
    List<StateTransitionId> transitions = new ArrayList<>(List.of(new StateTransitionId(uuid(6))));

    TemporalMutationResult result =
        new TemporalMutationResult(
            TemporalMutationDisposition.APPLIED,
            TransitionOperation.INVALIDATE,
            LINEAGE_ID,
            9,
            versions,
            transitions);
    versions.clear();
    transitions.clear();

    assertEquals(List.of(VERSION_ID), result.affectedVersionIds());
    assertEquals(List.of(new StateTransitionId(uuid(6))), result.transitionIds());
  }

  @Test
  void typedFailuresNeverContainCallerContent() {
    for (TemporalMutationFailureKind kind : TemporalMutationFailureKind.values()) {
      TemporalMutationException exception = new TemporalMutationException(kind);
      assertEquals(kind, exception.kind());
      assertFalse(exception.getMessage().contains("secret-marker"));
      assertFalse(exception.getMessage().contains("tenant-a"));
      assertFalse(exception.getMessage().contains("candidate"));
    }
  }

  @Test
  void malformedSelectionFailsBeforeCallingAnAuthority() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new CorrectionSelection(
                    SCOPE,
                    LINEAGE_ID,
                    VERSION_ID,
                    uuid(3),
                    uuid(4),
                    " ",
                    0,
                    "USER_CORRECTION",
                    "trace-1",
                    Instant.EPOCH));
    assertEquals("idempotencyKey must not be blank", exception.getMessage());

    IllegalArgumentException unsafeReason =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new InvalidationSelection(
                    SCOPE,
                    LINEAGE_ID,
                    VERSION_ID,
                    uuid(3),
                    "invalidation-key",
                    0,
                    "contains private content",
                    "trace-1",
                    Instant.EPOCH));
    assertEquals("reason must be an uppercase reason code", unsafeReason.getMessage());
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }
}
