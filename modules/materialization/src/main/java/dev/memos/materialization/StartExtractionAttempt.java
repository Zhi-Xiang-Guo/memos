package dev.memos.materialization;

import java.time.Instant;
import java.util.Objects;

public record StartExtractionAttempt(
    ExtractionAttemptId attemptId,
    ClaimedJob job,
    ExtractionProviderIdentity providerIdentity,
    String policyVersion,
    Instant startedAt) {
  public StartExtractionAttempt {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(providerIdentity, "providerIdentity must not be null");
    policyVersion = MaterializationTextValidation.requireText(policyVersion, "policyVersion", 128);
    Objects.requireNonNull(startedAt, "startedAt must not be null");
  }
}
