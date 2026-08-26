package dev.memos.ingestion;

import java.util.Objects;
import java.util.regex.Pattern;

public record Sha256Fingerprint(String hex) {
  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

  public Sha256Fingerprint {
    Objects.requireNonNull(hex, "hex must not be null");
    if (!SHA_256_HEX.matcher(hex).matches()) {
      throw new IllegalArgumentException("hex must be a lowercase SHA-256 value");
    }
  }

  @Override
  public String toString() {
    return hex;
  }
}
