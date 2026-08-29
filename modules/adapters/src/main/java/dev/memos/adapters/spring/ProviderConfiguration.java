package dev.memos.adapters.spring;

import dev.memos.adapters.embedding.EmbeddingAdapter;
import dev.memos.adapters.embedding.OllamaEmbeddingAdapter;
import dev.memos.adapters.fake.DeterministicEmbeddingAdapter;
import dev.memos.adapters.fake.FakeStructuredExtractionAdapter;
import dev.memos.adapters.fake.PassThroughRerankerAdapter;
import dev.memos.adapters.system.UuidMemoryIdGenerator;
import dev.memos.domain.MemoryIdGenerator;
import dev.memos.materialization.StructuredExtractionPort;
import dev.memos.retrieval.RerankerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class ProviderConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  MemoryIdGenerator memoryIdGenerator() {
    return new UuidMemoryIdGenerator();
  }

  @Bean
  StructuredExtractionPort structuredExtractionPort() {
    return new FakeStructuredExtractionAdapter();
  }

  @Bean
  EmbeddingAdapter embeddingPort(EmbeddingProperties properties) {
    String provider = required(properties.provider(), "memos.embedding.provider");
    String modelVersion = required(properties.modelVersion(), "memos.embedding.model-version");
    return switch (provider) {
      case "fake" -> {
        if (properties.dimensions() != DeterministicEmbeddingAdapter.DIMENSIONS) {
          throw new IllegalArgumentException(
              "fake embedding dimensions must match the deterministic adapter");
        }
        yield new DeterministicEmbeddingAdapter(modelVersion);
      }
      case "ollama" ->
          new OllamaEmbeddingAdapter(
              HttpClient.newBuilder().connectTimeout(properties.timeout()).build(),
              URI.create(required(properties.baseUrl(), "memos.embedding.base-url")),
              required(properties.modelTag(), "memos.embedding.model-tag"),
              modelVersion,
              required(properties.modelDigest(), "memos.embedding.model-digest"),
              properties.dimensions(),
              properties.timeout());
      default -> throw new IllegalArgumentException("unsupported memos.embedding.provider");
    };
  }

  @Bean
  RerankerPort rerankerPort() {
    return new PassThroughRerankerAdapter();
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must be configured");
    }
    return value;
  }
}
