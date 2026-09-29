package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.answering")
public record AnsweringProperties(
    String provider,
    String baseUrl,
    String model,
    Duration timeout,
    Duration requestTimeout,
    int maxAttempts,
    int maxConcurrent) {
  public AnsweringProperties {
    if (provider == null) provider = "fake";
    if (baseUrl == null) baseUrl = "http://localhost:11434";
    if (model == null) model = "qwen3:4b";
    if (timeout == null) timeout = Duration.ofSeconds(60);
    if (requestTimeout == null) requestTimeout = Duration.ofSeconds(20);
    if (maxAttempts == 0) maxAttempts = 2;
    if (maxConcurrent == 0) maxConcurrent = 8;
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(5)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 64)
      throw new IllegalArgumentException("invalid answering configuration");
  }
}
