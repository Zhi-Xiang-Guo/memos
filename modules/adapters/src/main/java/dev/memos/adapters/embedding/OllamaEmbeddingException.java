package dev.memos.adapters.embedding;

/** Content-safe Ollama failure classification. */
public final class OllamaEmbeddingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    RATE_LIMIT(true),
    SERVER_ERROR(true),
    TIMEOUT(true),
    TRANSPORT(true),
    CLIENT_ERROR(false),
    MALFORMED_RESPONSE(false),
    RESPONSE_TOO_LARGE(false),
    MODEL_NOT_FOUND(false),
    MODEL_DRIFT(false),
    MODEL_VERSION_MISMATCH(false),
    CAPABILITY_MISMATCH(false),
    DIMENSION_MISMATCH(false);

    private final boolean retryable;

    Kind(boolean retryable) {
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }
  }

  private final Kind kind;

  OllamaEmbeddingException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  OllamaEmbeddingException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
