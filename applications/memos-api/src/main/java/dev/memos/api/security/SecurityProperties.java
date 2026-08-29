package dev.memos.api.security;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.security")
public record SecurityProperties(String issuer, String audience, String hmacSecret) {
  public SecurityProperties {
    issuer = required(issuer, "memos.security.issuer");
    audience = required(audience, "memos.security.audience");
    hmacSecret = required(hmacSecret, "memos.security.hmac-secret");
    if (hmacSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException(
          "memos.security.hmac-secret must contain at least 32 bytes");
    }
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must be configured");
    }
    return value;
  }
}
