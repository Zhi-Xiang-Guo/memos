package dev.memos.adapters.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.retrieval")
public record RetrievalProperties(
    String embeddingModelVersion,
    String rerankerModelVersion,
    boolean rerankingEnabled,
    Duration rerankerTimeout,
    int rrfK,
    String operatorKey) {
  public RetrievalProperties {
    if (rerankerTimeout == null) {
      rerankerTimeout = Duration.ofMillis(150);
    }
    if (rrfK == 0) {
      rrfK = 60;
    }
  }
}
