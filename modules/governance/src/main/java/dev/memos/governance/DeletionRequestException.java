package dev.memos.governance;

import java.io.Serial;

public final class DeletionRequestException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final DeletionRequestFailure failure;

  public DeletionRequestException(DeletionRequestFailure failure) {
    super(failure.name());
    this.failure = failure;
  }

  public DeletionRequestFailure failure() {
    return failure;
  }
}
