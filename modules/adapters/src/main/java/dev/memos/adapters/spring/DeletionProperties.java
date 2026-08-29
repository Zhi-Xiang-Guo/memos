package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.deletion")
public record DeletionProperties(
    String policyVersion,
    int maxAttempts,
    String workerId,
    int batchSize,
    Duration leaseDuration,
    Duration backoffBase,
    Duration backoffCap) {
  public DeletionProperties {
    if (maxAttempts == 0) {
      maxAttempts = 5;
    }
    if (batchSize == 0) {
      batchSize = 8;
    }
    if (leaseDuration == null) {
      leaseDuration = Duration.ofSeconds(30);
    }
    if (backoffBase == null) {
      backoffBase = Duration.ofSeconds(1);
    }
    if (backoffCap == null) {
      backoffCap = Duration.ofMinutes(1);
    }
  }
}
