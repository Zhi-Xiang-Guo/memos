package dev.memos.domain.temporal;

import dev.memos.domain.candidate.MemoryType;
import java.util.Objects;

public record MemoryListQuery(
    LineageScope scope, MemoryType memoryType, AssertionStatus status, String cursor, int limit) {
  public MemoryListQuery {
    Objects.requireNonNull(scope, "scope must not be null");
    if (cursor != null) {
      cursor = TemporalValidation.text(cursor, "cursor", 512);
    }
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be in [1,100]");
    }
  }
}
