package dev.memos.api.http;

import java.time.Instant;

public record MaterializationJobResponse(
    String jobId,
    String sourceEventId,
    String state,
    int attempt,
    int maxAttempts,
    Instant nextAttemptAt,
    Instant leaseExpiresAt,
    String errorClass,
    int replayCount,
    Instant createdAt,
    Instant updatedAt) {}
