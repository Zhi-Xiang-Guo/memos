package dev.memos.materialization;

import java.util.List;
import java.util.Objects;

public record ProjectionEmbedding(
    List<Float> vector, String provider, String modelVersion, long inputTokens) {
  public ProjectionEmbedding {
    vector = List.copyOf(Objects.requireNonNull(vector, "vector must not be null"));
    if (vector.isEmpty() || vector.size() > 16_384) {
      throw new IllegalArgumentException("vector dimensions must be in [1,16384]");
    }
    if (vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
      throw new IllegalArgumentException("vector values must be finite");
    }
    provider = required(provider, "provider");
    modelVersion = required(modelVersion, "modelVersion");
    if (inputTokens < 0) {
      throw new IllegalArgumentException("inputTokens must not be negative");
    }
  }

  public int dimensions() {
    return vector.size();
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
    }
    return value;
  }
}
