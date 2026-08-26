package dev.memos.materialization;

import java.util.Objects;
import java.util.UUID;

public record ExtractionAttemptId(UUID value) {
  public ExtractionAttemptId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
