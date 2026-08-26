package dev.memos.domain.temporal;

import java.util.Objects;
import java.util.UUID;

public record AssertionVersionId(UUID value) {
  public AssertionVersionId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
