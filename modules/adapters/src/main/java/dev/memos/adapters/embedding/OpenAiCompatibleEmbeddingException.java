package dev.memos.adapters.embedding;

/** Content-safe failure classification shared by API retrieval and durable projection jobs. */
public final class OpenAiCompatibleEmbeddingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    RATE_LIMIT(true),
    SERVER_ERROR(true),
    TIMEOUT(true),
    TRANSPORT(true),
    CLIENT_ERROR(false),
    MALFORMED_RESPONSE(false),
    RESPONSE_TOO_LARGE(false),
    MODEL_VERSION_MISMATCH(false),
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

  OpenAiCompatibleEmbeddingException(Kind kind) {
    super("OPENAI_COMPATIBLE_EMBEDDING_" + kind.name());
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
