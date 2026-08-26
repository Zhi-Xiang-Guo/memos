package dev.memos.materialization;

import java.util.Objects;
import java.util.UUID;

public record CandidateId(UUID value) {
  public CandidateId {
    Objects.requireNonNull(value, "value must not be null");
  }
}
