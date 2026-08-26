package dev.memos.materialization;

import java.util.Objects;
import java.util.UUID;

public record QuarantineId(UUID value) {
  public QuarantineId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
