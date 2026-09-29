package dev.memos.answering.model;

public final class AnswerFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;
  private final boolean retryable;

  public AnswerFailure(String code, boolean retryable) {
    super(code);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
