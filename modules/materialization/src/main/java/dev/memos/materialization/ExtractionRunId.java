package dev.memos.materialization;

import java.util.Objects;
import java.util.UUID;

public record ExtractionRunId(UUID value) {
  public ExtractionRunId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
