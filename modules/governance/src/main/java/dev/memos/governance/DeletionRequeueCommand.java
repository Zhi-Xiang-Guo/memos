package dev.memos.governance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeletionRequeueCommand(
    MemoryScope requesterScope,
    String requesterSubjectId,
    DeletionAuthority authority,
    UUID operationId,
    String traceId,
    Instant requestedAt) {
  public DeletionRequeueCommand {
    Objects.requireNonNull(requesterScope, "requesterScope must not be null");
    requesterSubjectId = requireText(requesterSubjectId, "requesterSubjectId", 200);
    if (authority != DeletionAuthority.PRIVACY_ADMIN) {
      throw new IllegalArgumentException("deletion requeue requires privacy-admin authority");
    }
    Objects.requireNonNull(operationId, "operationId must not be null");
    traceId = requireText(traceId, "traceId", 128);
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
  }

  private static String requireText(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be non-blank and bounded");
    }
    return value;
  }
}
