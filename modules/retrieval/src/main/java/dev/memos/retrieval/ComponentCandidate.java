package dev.memos.retrieval;

import java.util.Objects;

public record ComponentCandidate(ProjectedMemory memory, ComponentSignal signal) {
  public ComponentCandidate {
    Objects.requireNonNull(memory, "memory must not be null");
    Objects.requireNonNull(signal, "signal must not be null");
  }
}
