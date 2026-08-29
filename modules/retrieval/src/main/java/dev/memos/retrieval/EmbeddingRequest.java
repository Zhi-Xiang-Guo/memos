package dev.memos.retrieval;

import java.util.Objects;

public record EmbeddingRequest(String text, String modelVersion) {
  public EmbeddingRequest {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    if (text.isBlank() || text.length() > 16_384) {
      throw new IllegalArgumentException("text must contain 1 to 16384 characters");
    }
    if (modelVersion.isBlank() || modelVersion.length() > 128) {
      throw new IllegalArgumentException("modelVersion must contain 1 to 128 characters");
    }
  }
}
