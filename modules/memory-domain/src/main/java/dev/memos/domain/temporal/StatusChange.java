package dev.memos.domain.temporal;

import java.util.Objects;

public record StatusChange(
    AssertionVersionId versionId, AssertionStatus fromStatus, AssertionStatus toStatus) {
  public StatusChange {
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(toStatus, "toStatus must not be null");
    if (fromStatus == toStatus) {
      throw new IllegalArgumentException("status change must change state");
    }
  }
}
