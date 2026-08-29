package dev.memos.adapters.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.audit")
public record AuditProperties(String policyVersion) {}
