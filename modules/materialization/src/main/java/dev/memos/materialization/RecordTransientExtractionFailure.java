package dev.memos.materialization;

import java.time.Instant;
import java.util.Objects;

public record RecordTransientExtractionFailure(
    ExtractionAttemptId attemptId,
    ClaimedJob job,
    JobErrorClass errorClass,
    ProviderCallMetadata providerMetadata,
    Instant recordedAt) {
  public RecordTransientExtractionFailure {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(errorClass, "errorClass must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
  }
}
