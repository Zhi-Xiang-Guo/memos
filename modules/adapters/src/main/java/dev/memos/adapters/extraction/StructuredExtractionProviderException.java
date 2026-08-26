package dev.memos.adapters.extraction;

/** Provider failure that never includes credentials, request content, or response bodies. */
public final class StructuredExtractionProviderException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    RATE_LIMIT,
    SERVER_ERROR,
    CLIENT_ERROR,
    TIMEOUT,
    TRANSPORT,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE
  }

  private final Kind kind;

  StructuredExtractionProviderException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  StructuredExtractionProviderException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
