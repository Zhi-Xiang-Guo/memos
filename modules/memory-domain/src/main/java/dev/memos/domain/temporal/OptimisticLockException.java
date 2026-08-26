package dev.memos.domain.temporal;

import java.io.Serial;

public final class OptimisticLockException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public OptimisticLockException(long expected, long actual) {
    super("expected lock version " + expected + " but was " + actual);
  }
}
