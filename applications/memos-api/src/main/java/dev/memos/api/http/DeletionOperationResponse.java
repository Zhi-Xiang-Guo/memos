package dev.memos.api.http;

import java.time.Instant;

public record DeletionOperationResponse(
    String operationId,
    String targetType,
    String state,
    String disposition,
    Steps steps,
    int attempt,
    int maxAttempts,
    Instant nextAttemptAt,
    String errorClass,
    Instant requestedAt,
    Instant completedAt) {
  public record Steps(String source, String authority, String projection, String jobs) {}
}
