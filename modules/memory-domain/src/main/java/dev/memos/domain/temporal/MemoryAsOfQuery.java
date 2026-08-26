package dev.memos.domain.temporal;

import java.time.Instant;
import java.util.Objects;

public record MemoryAsOfQuery(
    LineageScope scope, MemoryLineageId lineageId, Instant asOfInclusive) {
  public MemoryAsOfQuery {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    Objects.requireNonNull(asOfInclusive, "asOfInclusive must not be null");
  }
}
