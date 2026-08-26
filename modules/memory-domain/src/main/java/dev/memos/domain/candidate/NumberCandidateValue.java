package dev.memos.domain.candidate;

import java.math.BigDecimal;
import java.util.Objects;

public record NumberCandidateValue(BigDecimal value) implements CandidateValue {
  public NumberCandidateValue {
    Objects.requireNonNull(value, "value must not be null");
    if (value.precision() > 64 || Math.abs(value.scale()) > 64) {
      throw new IllegalArgumentException("number value exceeds the bounded precision");
    }
    value = value.stripTrailingZeros();
  }

  @Override
  public String canonicalJson() {
    return value.toPlainString();
  }
}
