package dev.memos.ingestion;

import java.io.Serial;
import java.util.Objects;

public final class IngestionConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final IngestionConflict reason;

  public IngestionConflictException(IngestionConflict reason) {
    super(messageFor(reason));
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  public IngestionConflict reason() {
    return reason;
  }

  private static String messageFor(IngestionConflict reason) {
    return switch (Objects.requireNonNull(reason, "reason must not be null")) {
      case IDEMPOTENCY_KEY_REUSED -> "idempotency key is already bound to another request";
      case SOURCE_ID_REUSED -> "source ID is already bound to another request";
      case SOURCE_ERASED -> "source content was erased and cannot be replayed";
      case USER_SCOPE_ERASED -> "the user scope is governed by an erasure operation";
    };
  }
}
