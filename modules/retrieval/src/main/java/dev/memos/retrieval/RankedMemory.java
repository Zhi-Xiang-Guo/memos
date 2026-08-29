package dev.memos.retrieval;

import java.util.List;
import java.util.Objects;

public record RankedMemory(
    ProjectedMemory memory,
    double fusedScore,
    List<ComponentSignal> componentSignals,
    Integer rerankRank) {
  public RankedMemory {
    Objects.requireNonNull(memory, "memory must not be null");
    if (!Double.isFinite(fusedScore) || fusedScore < 0) {
      throw new IllegalArgumentException("fusedScore must be finite and non-negative");
    }
    componentSignals =
        List.copyOf(Objects.requireNonNull(componentSignals, "componentSignals must not be null"));
    if (componentSignals.isEmpty()) {
      throw new IllegalArgumentException("componentSignals must not be empty");
    }
    if (rerankRank != null && rerankRank < 1) {
      throw new IllegalArgumentException("rerankRank must be positive");
    }
  }
}
