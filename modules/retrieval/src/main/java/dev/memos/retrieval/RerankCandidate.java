package dev.memos.retrieval;

import java.util.Objects;
import java.util.UUID;

public record RerankCandidate(UUID versionId, String content, double fusedScore) {
  public RerankCandidate {
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(content, "content must not be null");
    if (content.isBlank() || content.length() > 8_192) {
      throw new IllegalArgumentException("content must contain 1 to 8192 characters");
    }
    if (!Double.isFinite(fusedScore) || fusedScore < 0) {
      throw new IllegalArgumentException("fusedScore must be finite and non-negative");
    }
  }
}
