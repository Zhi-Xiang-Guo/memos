package dev.memos.retrieval;

import java.util.Objects;

public record RetrievalGateDecision(boolean retrieve, String reason) {
  public RetrievalGateDecision {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank() || reason.length() > 64) {
      throw new IllegalArgumentException("reason must contain 1 to 64 characters");
    }
  }
}
