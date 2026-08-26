package dev.memos.materialization;

import java.util.regex.Pattern;

public record JobErrorClass(String value) {
  private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

  public JobErrorClass {
    if (value == null || !SAFE_VALUE.matcher(value).matches()) {
      throw new IllegalArgumentException("value must be a safe error classification");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
