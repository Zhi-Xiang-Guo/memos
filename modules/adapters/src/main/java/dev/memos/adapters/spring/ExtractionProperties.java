package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.extraction")
public record ExtractionProperties(
    String provider,
    String baseUrl,
    String apiKey,
    String modelVersion,
    String promptVersion,
    String schemaVersion,
    String policyVersion,
    Duration timeout) {}
