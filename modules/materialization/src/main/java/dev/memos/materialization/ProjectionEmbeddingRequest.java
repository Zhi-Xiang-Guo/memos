package dev.memos.materialization;

import java.util.Objects;

public record ProjectionEmbeddingRequest(String content, String modelVersion) {
  public ProjectionEmbeddingRequest {
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    if (content.isBlank() || content.length() > 8_192) {
      throw new IllegalArgumentException("content must contain 1 to 8192 characters");
    }
    if (modelVersion.isBlank() || modelVersion.length() > 128) {
      throw new IllegalArgumentException("modelVersion must contain 1 to 128 characters");
    }
  }
}
