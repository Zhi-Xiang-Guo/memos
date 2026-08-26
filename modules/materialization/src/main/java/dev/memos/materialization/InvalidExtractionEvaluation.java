package dev.memos.materialization;

import java.util.Objects;

public record InvalidExtractionEvaluation(
    ProviderCallMetadata providerMetadata, ProposalDecodingError error, String path)
    implements CandidateExtractionEvaluation {
  public InvalidExtractionEvaluation {
    Objects.requireNonNull(providerMetadata, "providerMetadata must not be null");
    Objects.requireNonNull(error, "error must not be null");
    path = MaterializationTextValidation.requireText(path, "path", 256);
  }
}
