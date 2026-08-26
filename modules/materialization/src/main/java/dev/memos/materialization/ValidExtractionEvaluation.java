package dev.memos.materialization;

import java.util.List;
import java.util.Objects;

public record ValidExtractionEvaluation(
    ProviderCallMetadata providerMetadata, List<EvaluatedCandidate> candidates)
    implements CandidateExtractionEvaluation {
  public ValidExtractionEvaluation {
    Objects.requireNonNull(providerMetadata, "providerMetadata must not be null");
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
  }
}
