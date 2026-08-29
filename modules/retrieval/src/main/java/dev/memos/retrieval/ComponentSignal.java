package dev.memos.retrieval;

import java.util.Objects;

public record ComponentSignal(CandidateSource source, int rank, double rawScore) {
  public ComponentSignal {
    Objects.requireNonNull(source, "source must not be null");
    if (rank < 1) {
      throw new IllegalArgumentException("rank must be positive");
    }
    if (!Double.isFinite(rawScore)) {
      throw new IllegalArgumentException("rawScore must be finite");
    }
  }
}
