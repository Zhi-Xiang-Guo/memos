package dev.memos.adapters.spring;

import dev.memos.adapters.fake.DeterministicEmbeddingAdapter;
import dev.memos.adapters.fake.FakeStructuredExtractionAdapter;
import dev.memos.adapters.fake.PassThroughRerankerAdapter;
import dev.memos.adapters.system.UuidMemoryIdGenerator;
import dev.memos.domain.MemoryIdGenerator;
import dev.memos.materialization.StructuredExtractionPort;
import dev.memos.retrieval.EmbeddingPort;
import dev.memos.retrieval.RerankerPort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
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
  EmbeddingPort embeddingPort() {
    return new DeterministicEmbeddingAdapter();
  }

  @Bean
  RerankerPort rerankerPort() {
    return new PassThroughRerankerAdapter();
  }
}
