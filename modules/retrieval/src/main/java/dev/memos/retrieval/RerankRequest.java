package dev.memos.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RerankRequest(
    String query, List<RerankCandidate> candidates, String modelVersion, Instant deadline) {
  public RerankRequest {
    Objects.requireNonNull(query, "query must not be null");
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    if (query.isBlank() || query.length() > 4_096) {
      throw new IllegalArgumentException("query must contain 1 to 4096 characters");
    }
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException("candidates must not be empty");
    }
  }
}
