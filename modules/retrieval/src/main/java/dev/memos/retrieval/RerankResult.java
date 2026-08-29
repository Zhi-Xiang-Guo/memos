package dev.memos.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RerankResult(
    List<UUID> orderedVersionIds, String provider, String modelVersion, long inputTokens) {
  public RerankResult {
    orderedVersionIds =
        List.copyOf(
            Objects.requireNonNull(orderedVersionIds, "orderedVersionIds must not be null"));
    provider = required(provider, "provider");
    modelVersion = required(modelVersion, "modelVersion");
    if (inputTokens < 0) {
      throw new IllegalArgumentException("inputTokens must not be negative");
    }
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
    }
    return value;
  }
}
