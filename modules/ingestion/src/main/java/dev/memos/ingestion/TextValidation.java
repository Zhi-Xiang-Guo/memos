package dev.memos.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class TextValidation {
  private TextValidation() {}

  static String requireText(String value, String name, int maxLength) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
    }
    return value;
  }

  static String requirePayload(String value, int maxBytes) {
    Objects.requireNonNull(value, "payload must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("payload must not be blank");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException("payload must not exceed " + maxBytes + " UTF-8 bytes");
    }
    return value;
  }
}
