package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.worker")
public record WorkerProperties(
    boolean enabled,
    String workerId,
    int batchSize,
    Duration leaseDuration,
    Duration backoffBase,
    Duration backoffCap) {}
