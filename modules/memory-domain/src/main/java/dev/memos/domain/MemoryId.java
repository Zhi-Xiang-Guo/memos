package dev.memos.domain;

import java.util.Objects;

public record MemoryId(String value) {
  public MemoryId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
