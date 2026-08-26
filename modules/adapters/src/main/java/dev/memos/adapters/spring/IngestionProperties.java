package dev.memos.adapters.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.ingestion")
public record IngestionProperties(String policyVersion, String modelVersion, int maxAttempts) {}
