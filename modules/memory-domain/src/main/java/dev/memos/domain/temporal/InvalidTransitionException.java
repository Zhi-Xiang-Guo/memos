package dev.memos.domain.temporal;

import java.io.Serial;

public final class InvalidTransitionException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public InvalidTransitionException(String message) {
    super(message);
  }
}
