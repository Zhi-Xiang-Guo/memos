package dev.memos.adapters.spring;

import dev.memos.adapters.fake.DeterministicFakeMaterializationHandler;
import dev.memos.adapters.metrics.MicrometerOutboxWorkerTelemetry;
import dev.memos.adapters.observability.TracingMaterializationJobHandler;
import dev.memos.adapters.postgres.JdbcMaterializationJobStore;
import dev.memos.materialization.ExponentialBackoffPolicy;
import dev.memos.materialization.MaterializationJobHandler;
import dev.memos.materialization.MaterializationJobStore;
import dev.memos.materialization.OutboxWorkerService;
import dev.memos.materialization.OutboxWorkerTelemetry;
import dev.memos.materialization.WorkerId;
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
@EnableConfigurationProperties(WorkerProperties.class)
public class MaterializationConfiguration {
  @Bean
  MaterializationJobStore materializationJobStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new JdbcMaterializationJobStore(jdbc, new TransactionTemplate(transactionManager));
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  MaterializationJobHandler materializationJobHandler() {
    return new TracingMaterializationJobHandler(new DeterministicFakeMaterializationHandler());
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  OutboxWorkerTelemetry outboxWorkerTelemetry(MeterRegistry registry) {
    return new MicrometerOutboxWorkerTelemetry(registry);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  OutboxWorkerService outboxWorkerService(
      Clock clock,
      MaterializationJobStore store,
      MaterializationJobHandler handler,
      OutboxWorkerTelemetry telemetry,
      WorkerProperties properties) {
    return new OutboxWorkerService(
        clock,
        store,
        handler,
        new ExponentialBackoffPolicy(properties.backoffBase(), properties.backoffCap()),
        telemetry,
        new WorkerId(effectiveWorkerId(properties.workerId())),
        properties.batchSize(),
        properties.leaseDuration());
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
    return host + "-" + UUID.randomUUID();
  }
}
