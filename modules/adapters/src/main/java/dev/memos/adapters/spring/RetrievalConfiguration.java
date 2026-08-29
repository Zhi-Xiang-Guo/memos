package dev.memos.adapters.spring;

import dev.memos.adapters.metrics.MicrometerRetrievalTelemetry;
import dev.memos.adapters.postgres.JdbcRetrievalCandidateStore;
import dev.memos.context.CodePointTokenCounter;
import dev.memos.context.ContextTokenCounter;
import dev.memos.context.MemoryContextAssembler;
import dev.memos.retrieval.DeterministicQueryGate;
import dev.memos.retrieval.EmbeddingPort;
import dev.memos.retrieval.HybridRetrievalService;
import dev.memos.retrieval.QueryGate;
import dev.memos.retrieval.QueryIntentParser;
import dev.memos.retrieval.RerankerPort;
import dev.memos.retrieval.RetrievalCandidateStore;
import dev.memos.retrieval.RetrievalTelemetry;
import dev.memos.retrieval.RuleBasedQueryIntentParser;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfiguration {
  @Bean
  QueryGate queryGate() {
    return new DeterministicQueryGate();
  }

  @Bean
  QueryIntentParser queryIntentParser() {
    return new RuleBasedQueryIntentParser();
  }

  @Bean
  RetrievalCandidateStore retrievalCandidateStore(
      JdbcTemplate jdbc, EmbeddingProperties embeddingProperties) {
    return new JdbcRetrievalCandidateStore(jdbc, embeddingProperties.dimensions());
  }

  @Bean
  RetrievalTelemetry retrievalTelemetry(MeterRegistry registry) {
    return new MicrometerRetrievalTelemetry(registry);
  }

  @Bean
  HybridRetrievalService hybridRetrievalService(
      Clock clock,
      QueryGate queryGate,
      QueryIntentParser intentParser,
      EmbeddingPort embeddingPort,
      RetrievalCandidateStore candidateStore,
      RerankerPort rerankerPort,
      RetrievalTelemetry telemetry,
      RetrievalProperties properties) {
    return new HybridRetrievalService(
        clock,
        queryGate,
        intentParser,
        embeddingPort,
        candidateStore,
        rerankerPort,
        telemetry,
        required(properties.embeddingModelVersion(), "memos.retrieval.embedding-model-version"),
        required(properties.rerankerModelVersion(), "memos.retrieval.reranker-model-version"),
        properties.rrfK());
  }

  @Bean
  ContextTokenCounter contextTokenCounter() {
    return new CodePointTokenCounter();
  }

  @Bean
  MemoryContextAssembler memoryContextAssembler(ContextTokenCounter counter) {
    return new MemoryContextAssembler(counter);
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must be configured");
    }
    return value;
  }
}
