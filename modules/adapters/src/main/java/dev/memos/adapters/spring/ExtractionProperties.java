package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.extraction")
public record ExtractionProperties(
    String provider,
    String baseUrl,
    String apiKey,
    String modelTag,
    String modelVersion,
    String modelDigest,
    String promptVersion,
    String schemaVersion,
    String policyVersion,
    int seed,
    Duration timeout) {}
