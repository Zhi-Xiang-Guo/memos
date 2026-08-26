package dev.memos.materialization;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ExponentialBackoffPolicy {
  private final Duration base;
  private final Duration cap;

  public ExponentialBackoffPolicy(Duration base, Duration cap) {
    this.base = requirePositive(base, "base");
    this.cap = requirePositive(cap, "cap");
    if (base.compareTo(cap) > 0) {
      throw new IllegalArgumentException("base must not exceed cap");
    }
  }

  public Duration delayForAttempt(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    Duration delay = base;
    for (int index = 1; index < attempt && delay.compareTo(cap) < 0; index++) {
      try {
        Duration doubled = delay.multipliedBy(2);
        delay = doubled.compareTo(cap) > 0 ? cap : doubled;
      } catch (ArithmeticException exception) {
        return cap;
      }
    }
    return delay;
  }

  public Instant nextAttemptAt(Instant failedAt, int attempt) {
    Objects.requireNonNull(failedAt, "failedAt must not be null");
    return failedAt.plus(delayForAttempt(attempt));
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
