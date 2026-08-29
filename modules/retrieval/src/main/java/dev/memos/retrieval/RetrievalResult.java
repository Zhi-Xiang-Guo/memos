package dev.memos.retrieval;

import java.util.List;
import java.util.Objects;

public record RetrievalResult(
    RetrievalGateDecision gate,
    QueryIntent intent,
    List<RankedMemory> memories,
    RetrievalTrace trace) {
  public RetrievalResult {
    Objects.requireNonNull(gate, "gate must not be null");
    Objects.requireNonNull(intent, "intent must not be null");
    memories = List.copyOf(Objects.requireNonNull(memories, "memories must not be null"));
    Objects.requireNonNull(trace, "trace must not be null");
  }
}
