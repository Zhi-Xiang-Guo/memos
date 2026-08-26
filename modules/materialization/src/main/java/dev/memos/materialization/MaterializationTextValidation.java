package dev.memos.materialization;

import java.util.Objects;

final class MaterializationTextValidation {
  private MaterializationTextValidation() {}

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
}
