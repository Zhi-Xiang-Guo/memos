package dev.memos.domain.temporal;

import java.util.List;
import java.util.Objects;

public record MemoryLineagePage(List<MemoryLineageSummary> items, String nextCursor) {
  public MemoryLineagePage {
    items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    if (nextCursor != null) {
      nextCursor = TemporalValidation.text(nextCursor, "nextCursor", 512);
    }
  }
}
