package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.embedding")
public record EmbeddingProperties(
    String provider,
    String baseUrl,
    String apiKey,
    String modelTag,
    String modelVersion,
    String modelDigest,
    int dimensions,
    Duration timeout) {}
