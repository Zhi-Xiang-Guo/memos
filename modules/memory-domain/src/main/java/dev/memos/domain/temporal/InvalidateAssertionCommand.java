package dev.memos.domain.temporal;

import java.time.Instant;
import java.util.Objects;

public record InvalidateAssertionCommand(
    LineageScope scope,
    MemoryLineageId lineageId,
    AssertionVersionId versionId,
    TransitionContext transitionContext,
    String reason,
    Instant invalidatedAt,
    long expectedLockVersion) {
  public InvalidateAssertionCommand {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(transitionContext, "transitionContext must not be null");
    reason = TemporalValidation.text(reason, "reason", 256);
    Objects.requireNonNull(invalidatedAt, "invalidatedAt must not be null");
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion must not be negative");
    }
  }
}
