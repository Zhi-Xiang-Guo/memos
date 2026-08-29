package dev.memos.adapters.spring;

import dev.memos.adapters.metrics.MicrometerDeletionWorkerTelemetry;
import dev.memos.adapters.postgres.JdbcDeletionStore;
import dev.memos.adapters.system.UuidDeletionIdGenerator;
import dev.memos.governance.DeletionIdGenerator;
import dev.memos.governance.DeletionStore;
import dev.memos.governance.DeletionWorkerService;
import dev.memos.governance.DeletionWorkerTelemetry;
import dev.memos.governance.GovernedDeletionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeletionProperties.class)
public class GovernanceConfiguration {
  @Bean
  DeletionStore deletionStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      DeletionProperties properties) {
    return new JdbcDeletionStore(
        jdbc,
        new TransactionTemplate(transactionManager),
        UUID::randomUUID,
        required(properties.policyVersion(), "memos.deletion.policy-version"));
  }

  @Bean
  DeletionIdGenerator deletionIdGenerator() {
    return new UuidDeletionIdGenerator();
  }

  @Bean
  GovernedDeletionService governedDeletionService(
      DeletionStore store, DeletionIdGenerator identifiers, DeletionProperties properties) {
    return new GovernedDeletionService(store, identifiers, properties.maxAttempts());
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  DeletionWorkerTelemetry deletionWorkerTelemetry(MeterRegistry registry) {
    return new MicrometerDeletionWorkerTelemetry(registry);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  DeletionWorkerService deletionWorkerService(
      Clock clock,
      DeletionStore store,
      DeletionWorkerTelemetry telemetry,
      DeletionProperties properties) {
    return new DeletionWorkerService(
        clock,
        store,
        telemetry,
        effectiveWorkerId(properties.workerId()),
        properties.batchSize(),
        properties.leaseDuration(),
        properties.backoffBase(),
        properties.backoffCap());
  }

  private static String effectiveWorkerId(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException exception) {
      host = "unknown-host";
    }
    return "deletion-" + host + "-" + UUID.randomUUID();
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must be configured");
    }
    return value;
  }
}
