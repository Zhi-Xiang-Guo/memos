package dev.memos.domain.temporal;

import java.util.List;
import java.util.Objects;

public record MemoryDiff(
    MemoryDiffQuery query,
    List<AssertionVersion> appendedVersions,
    List<AssertionStateTransition> transitions) {
  public MemoryDiff {
    Objects.requireNonNull(query, "query must not be null");
    appendedVersions = List.copyOf(Objects.requireNonNull(appendedVersions, "appendedVersions"));
    transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
  }
}
