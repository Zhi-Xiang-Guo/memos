package dev.memos.ingestion;

import java.time.Instant;
import java.util.Objects;

public record MaterializationIntent(
    MaterializationJobId jobId,
    SourceEventId sourceEventId,
    String tenantId,
    String semanticJobKey,
    String policyVersion,
    String modelVersion,
    int maxAttempts,
    Instant nextAttemptAt,
    String traceId,
    Instant createdAt) {
  public MaterializationIntent {
    Objects.requireNonNull(jobId, "jobId must not be null");
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    tenantId = TextValidation.requireText(tenantId, "tenantId", 128);
    semanticJobKey = TextValidation.requireText(semanticJobKey, "semanticJobKey", 300);
    policyVersion = TextValidation.requireText(policyVersion, "policyVersion", 128);
    modelVersion = TextValidation.requireText(modelVersion, "modelVersion", 128);
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    traceId = TextValidation.requireText(traceId, "traceId", 128);
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
