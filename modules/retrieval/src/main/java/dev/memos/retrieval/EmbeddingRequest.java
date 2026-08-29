package dev.memos.retrieval;

import java.util.Objects;

public record EmbeddingRequest(String text, String modelVersion) {
  private static final int MAX_TEXT_CHARACTERS = 131_072;

  public EmbeddingRequest {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    if (text.isBlank() || text.length() > MAX_TEXT_CHARACTERS) {
      throw new IllegalArgumentException("text must contain 1 to 131072 characters");
    }
    if (modelVersion.isBlank() || modelVersion.length() > 128) {
      throw new IllegalArgumentException("modelVersion must contain 1 to 128 characters");
    }
  }
}
