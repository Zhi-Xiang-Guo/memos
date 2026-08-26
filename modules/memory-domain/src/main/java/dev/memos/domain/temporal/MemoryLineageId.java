package dev.memos.domain.temporal;

import java.util.Objects;
import java.util.UUID;

public record MemoryLineageId(UUID value) {
  public MemoryLineageId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
