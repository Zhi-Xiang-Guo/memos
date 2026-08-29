package dev.memos.retrieval;

import java.util.Locale;
import java.util.Set;

public final class DeterministicQueryGate implements QueryGate {
  private static final Set<String> NON_RETRIEVAL =
      Set.of("hi", "hello", "thanks", "thank you", "你好", "谢谢", "早上好", "晚上好");

  @Override
  public RetrievalGateDecision decide(String query) {
    if (query == null || query.isBlank()) {
      return new RetrievalGateDecision(false, "EMPTY_QUERY");
    }
    String normalized = query.strip().toLowerCase(Locale.ROOT);
    if (NON_RETRIEVAL.contains(normalized)) {
      return new RetrievalGateDecision(false, "CONVERSATIONAL_NO_MEMORY_NEED");
    }
    return new RetrievalGateDecision(true, "MEMORY_RETRIEVAL_REQUIRED");
  }
}
