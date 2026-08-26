package dev.memos.materialization;

import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.TransitionOperation;
import java.util.List;
import java.util.Objects;

/** Content-free mutation receipt suitable for a public API response. */
public record TemporalMutationResult(
    TemporalMutationDisposition disposition,
    TransitionOperation operation,
    MemoryLineageId lineageId,
    long lockVersion,
    List<AssertionVersionId> affectedVersionIds,
    List<StateTransitionId> transitionIds) {
  public TemporalMutationResult {
    Objects.requireNonNull(disposition, "disposition must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    if (lockVersion < 0) {
      throw new IllegalArgumentException("lockVersion must not be negative");
    }
    affectedVersionIds =
        List.copyOf(Objects.requireNonNull(affectedVersionIds, "affectedVersionIds"));
    transitionIds = List.copyOf(Objects.requireNonNull(transitionIds, "transitionIds"));
    if (disposition == TemporalMutationDisposition.APPLIED && transitionIds.isEmpty()) {
      throw new IllegalArgumentException("an applied mutation requires a transition id");
    }
  }
}
