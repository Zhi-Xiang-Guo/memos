package dev.memos.domain.temporal;

import java.util.Objects;
import java.util.UUID;

public record StateTransitionId(UUID value) {
  public StateTransitionId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
