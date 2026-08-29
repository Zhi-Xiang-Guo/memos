package dev.memos.retrieval;

import java.util.Objects;

public record RetrievalTrace(
    String gateReason,
    TemporalQueryIntent temporalIntent,
    int componentCandidateCount,
    int fusedCandidateCount,
    String rerankOutcome,
    String embeddingProvider,
    String embeddingModelVersion,
    long embeddingInputTokens) {
  public RetrievalTrace {
    Objects.requireNonNull(gateReason, "gateReason must not be null");
    Objects.requireNonNull(temporalIntent, "temporalIntent must not be null");
    Objects.requireNonNull(rerankOutcome, "rerankOutcome must not be null");
    Objects.requireNonNull(embeddingProvider, "embeddingProvider must not be null");
    Objects.requireNonNull(embeddingModelVersion, "embeddingModelVersion must not be null");
  }
}
