package dev.memos.ingestion;

import java.util.Objects;
import java.util.UUID;

public record MaterializationJobId(UUID value) {
  public MaterializationJobId {
    Objects.requireNonNull(value, "value must not be null");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
