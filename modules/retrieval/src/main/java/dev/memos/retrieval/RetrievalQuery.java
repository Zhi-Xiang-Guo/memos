package dev.memos.retrieval;

import dev.memos.domain.temporal.LineageScope;
import java.time.Instant;
import java.util.Objects;

public record RetrievalQuery(
    LineageScope scope,
    String query,
    RetrievalMode mode,
    int limit,
    int componentLimit,
    String predicate,
    String subjectLabel,
    Instant explicitTime,
    boolean rerank,
    Instant rerankDeadline) {
  public RetrievalQuery {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(mode, "mode must not be null");
    if (query.isBlank() || query.length() > 4_096) {
      throw new IllegalArgumentException("query must contain 1 to 4096 characters");
    }
    if (limit < 1 || limit > 50) {
      throw new IllegalArgumentException("limit must be in [1,50]");
    }
    if (componentLimit < limit || componentLimit > 200) {
      throw new IllegalArgumentException("componentLimit must be in [limit,200]");
    }
    if (rerank) {
      Objects.requireNonNull(rerankDeadline, "rerankDeadline must not be null when enabled");
    }
  }
}
