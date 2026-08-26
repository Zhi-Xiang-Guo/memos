package dev.memos.domain.temporal;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record MemoryLineageSummary(
    MemoryLineageIdentity identity,
    long lockVersion,
    Map<AssertionStatus, Integer> statusCounts,
    Instant lastTransitionAt) {
  public MemoryLineageSummary {
    Objects.requireNonNull(identity, "identity must not be null");
    if (lockVersion < 0) {
      throw new IllegalArgumentException("lockVersion must not be negative");
    }
    statusCounts = Map.copyOf(Objects.requireNonNull(statusCounts, "statusCounts"));
    if (statusCounts.values().stream().anyMatch(count -> count == null || count < 0)) {
      throw new IllegalArgumentException("statusCounts must not contain negative values");
    }
  }
}
