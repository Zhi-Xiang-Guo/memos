package dev.memos.adapters.spring;

import dev.memos.adapters.postgres.JdbcTraceAccessAudit;
import dev.memos.audit.TraceAccessAudit;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfiguration {
  @Bean
  TraceAccessAudit traceAccessAudit(JdbcTemplate jdbc, AuditProperties properties) {
    return new JdbcTraceAccessAudit(
        jdbc, UUID::randomUUID, required(properties.policyVersion(), "memos.audit.policy-version"));
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must be configured");
    }
    return value;
  }
}
