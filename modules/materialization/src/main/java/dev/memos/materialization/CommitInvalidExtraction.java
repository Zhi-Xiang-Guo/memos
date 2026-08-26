package dev.memos.materialization;

import java.time.Instant;
import java.util.Objects;

public record CommitInvalidExtraction(
    ExtractionAttemptId attemptId,
    QuarantineId quarantineId,
    ClaimedJob job,
    ProviderCallMetadata providerMetadata,
    ProposalDecodingError decodingError,
    String errorPath,
    Instant committedAt) {
  public CommitInvalidExtraction {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    Objects.requireNonNull(quarantineId, "quarantineId must not be null");
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(providerMetadata, "providerMetadata must not be null");
    Objects.requireNonNull(decodingError, "decodingError must not be null");
    errorPath = MaterializationTextValidation.requireText(errorPath, "errorPath", 256);
    Objects.requireNonNull(committedAt, "committedAt must not be null");
  }
}
