package dev.memos.materialization;

import java.io.Serial;
import java.util.Objects;

/** A typed, content-safe application failure. */
public final class TemporalMutationException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final TemporalMutationFailureKind kind;

  public TemporalMutationException(TemporalMutationFailureKind kind) {
    super(message(kind));
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
  }

  public TemporalMutationFailureKind kind() {
    return kind;
  }

  private static String message(TemporalMutationFailureKind kind) {
    Objects.requireNonNull(kind, "kind must not be null");
    return switch (kind) {
      case NOT_FOUND -> "scoped mutation evidence or target was not found";
      case STALE_PRECONDITION -> "the expected lineage version is stale";
      case IDEMPOTENCY_CONFLICT -> "the idempotency key is bound to different immutable input";
      case INVALID_TRANSITION -> "the requested memory transition is not allowed";
    };
  }
}
