package dev.memos.domain.temporal;

final class TemporalValidation {
  private TemporalValidation() {}

  static String text(String value, String field, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String normalized = value.strip();
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(field + " exceeds maximum length");
    }
    return normalized;
  }
}
