package dev.memos.domain.temporal;

import java.util.List;
import java.util.Objects;

public record TransitionPlan(
    TransitionOperation operation,
    List<AssertionVersion> appendedVersions,
    List<AssertionStateTransition> appendedTransitions,
    MemoryLineageSnapshot resultingSnapshot,
    boolean replayed) {
  public TransitionPlan {
    Objects.requireNonNull(operation, "operation must not be null");
    appendedVersions = List.copyOf(Objects.requireNonNull(appendedVersions, "appendedVersions"));
    appendedTransitions =
        List.copyOf(Objects.requireNonNull(appendedTransitions, "appendedTransitions"));
    Objects.requireNonNull(resultingSnapshot, "resultingSnapshot must not be null");
    if (operation == TransitionOperation.IGNORE
        && (!appendedVersions.isEmpty() || !appendedTransitions.isEmpty())) {
      throw new IllegalArgumentException("IGNORE must not append authoritative records");
    }
    if (replayed && operation != TransitionOperation.IGNORE) {
      throw new IllegalArgumentException("only ignored plans can be replayed");
    }
  }
}
