package dev.memos.adapters.spring;

import dev.memos.adapters.json.JacksonPayloadCanonicalizer;
import dev.memos.adapters.metrics.MicrometerIngestionTelemetry;
import dev.memos.adapters.postgres.JdbcSourceIngestionStore;
import dev.memos.adapters.system.UuidIngestionIdentifierGenerator;
import dev.memos.ingestion.IngestionIdentifierGenerator;
import dev.memos.ingestion.IngestionTelemetry;
import dev.memos.ingestion.PayloadCanonicalizer;
import dev.memos.ingestion.SourceIngestionService;
import dev.memos.ingestion.SourceIngestionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfiguration {
  @Bean
  PayloadCanonicalizer payloadCanonicalizer() {
    var mapper =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    return new JacksonPayloadCanonicalizer(mapper);
  }

  @Bean
  IngestionIdentifierGenerator ingestionIdentifierGenerator() {
    return new UuidIngestionIdentifierGenerator();
  }

  @Bean
  SourceIngestionStore sourceIngestionStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new JdbcSourceIngestionStore(jdbc, new TransactionTemplate(transactionManager));
  }

  @Bean
  IngestionTelemetry ingestionTelemetry(MeterRegistry registry) {
    return new MicrometerIngestionTelemetry(registry);
  }

  @Bean
  SourceIngestionService sourceIngestionService(
      Clock clock,
      IngestionIdentifierGenerator identifierGenerator,
      PayloadCanonicalizer payloadCanonicalizer,
      SourceIngestionStore store,
      IngestionTelemetry telemetry,
      IngestionProperties properties) {
    return new SourceIngestionService(
        clock,
        identifierGenerator,
        payloadCanonicalizer,
        store,
        telemetry,
        properties.policyVersion(),
        properties.modelVersion(),
        properties.maxAttempts());
  }
}
