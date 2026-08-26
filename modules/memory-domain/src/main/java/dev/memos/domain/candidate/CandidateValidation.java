package dev.memos.domain.candidate;

import java.util.Objects;

final class CandidateValidation {
  private CandidateValidation() {}

  static String requireText(String value, String name, int maximumLength) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must not exceed " + maximumLength + " characters");
    }
    return value;
  }

  static double requireUnitInterval(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " must be between 0 and 1");
    }
    return value;
  }
}
