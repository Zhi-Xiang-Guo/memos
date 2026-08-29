package dev.memos.api.http;

import java.time.Instant;

public record MaterializationJobResponse(
    String jobId,
    String sourceEventId,
    String jobType,
    String state,
    int attempt,
    int maxAttempts,
    Instant nextAttemptAt,
    Instant leaseExpiresAt,
    String errorClass,
    int replayCount,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt) {
  static MaterializationJobResponse from(dev.memos.materialization.MaterializationJob job) {
    return new MaterializationJobResponse(
        job.jobId().toString(),
        job.sourceEventId().toString(),
        job.jobType().name(),
        job.state().name(),
        job.attempt(),
        job.maxAttempts(),
        job.nextAttemptAt(),
        job.leaseExpiresAt(),
        job.errorClass() == null ? null : job.errorClass().value(),
        job.replayCount(),
        job.completedAt(),
        job.createdAt(),
        job.updatedAt());
  }
}
