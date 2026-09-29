package dev.memos.adapters.spring;

import dev.memos.adapters.answering.FakeAnswerModelAdapter;
import dev.memos.adapters.answering.MemosEvidenceSearchAdapter;
import dev.memos.adapters.answering.OllamaAnswerModelAdapter;
import dev.memos.adapters.answering.OllamaChatTransport;
import dev.memos.answering.port.AnswerModelPort;
import dev.memos.answering.port.EvidenceSearchPort;
import dev.memos.answering.service.RagAnswerService;
import dev.memos.context.MemoryEvidenceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnsweringProperties.class)
public class AnsweringConfiguration {
  @Bean
  AnswerModelPort answerModelPort(AnsweringProperties properties, Clock clock) {
    return switch (properties.provider()) {
      case "fake" -> new FakeAnswerModelAdapter();
      case "ollama" -> new OllamaAnswerModelAdapter(transport(properties, clock));
      default -> throw new IllegalArgumentException("unsupported answering provider");
    };
  }

  @Bean
  EvidenceSearchPort evidenceSearchPort(
      MemoryEvidenceService evidence, RetrievalProperties properties, Clock clock) {
    return new MemosEvidenceSearchAdapter(
        evidence, properties.rerankingEnabled(), properties.rerankerTimeout(), clock);
  }

  @Bean
  RagAnswerService ragAnswerService(
      EvidenceSearchPort search,
      AnswerModelPort model,
      Clock clock,
      AnsweringProperties properties) {
    return new RagAnswerService(search, model, clock, properties.timeout());
  }

  static OllamaChatTransport transport(AnsweringProperties properties, Clock clock) {
    return new OllamaChatTransport(
        HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build(),
        URI.create(properties.baseUrl()),
        properties.model(),
        clock,
        properties.requestTimeout(),
        properties.maxAttempts());
  }
}
