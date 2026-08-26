package dev.memos.domain.temporal;

import java.time.Instant;
import java.util.Objects;

public record MemoryDiffQuery(
    LineageScope scope, MemoryLineageId lineageId, Instant fromExclusive, Instant toInclusive) {
  public MemoryDiffQuery {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    Objects.requireNonNull(fromExclusive, "fromExclusive must not be null");
    Objects.requireNonNull(toInclusive, "toInclusive must not be null");
    if (!fromExclusive.isBefore(toInclusive)) {
      throw new IllegalArgumentException("fromExclusive must be before toInclusive");
    }
  }
}
