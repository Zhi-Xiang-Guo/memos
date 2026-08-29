package dev.memos.retrieval;

import dev.memos.domain.temporal.LineageScope;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record CandidateStoreQuery(
    LineageScope scope,
    String query,
    QueryIntent intent,
    String predicate,
    String subjectLabel,
    int componentLimit,
    Set<CandidateSource> sources,
    EmbeddingResult embedding) {
  public CandidateStoreQuery {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(intent, "intent must not be null");
    if (query.isBlank() || query.length() > 4_096) {
      throw new IllegalArgumentException("query must contain 1 to 4096 characters");
    }
    if (componentLimit < 1 || componentLimit > 200) {
      throw new IllegalArgumentException("componentLimit must be in [1,200]");
    }
    sources = Set.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("sources must not be empty");
    }
    Objects.requireNonNull(embedding, "embedding must not be null");
  }

  public Instant targetTime() {
    return intent.targetTime();
  }
}
