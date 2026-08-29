package dev.memos.audit;

import java.time.Instant;
import java.util.Objects;

public record TraceAccessAuditEvent(
    String tenantId,
    String userId,
    String agentId,
    String subjectId,
    String traceId,
    Instant occurredAt) {
  public TraceAccessAuditEvent {
    tenantId = required(tenantId, "tenantId", 128);
    userId = required(userId, "userId", 128);
    agentId = required(agentId, "agentId", 128);
    subjectId = required(subjectId, "subjectId", 200);
    traceId = required(traceId, "traceId", 128);
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
  }

  private static String required(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be non-blank and bounded");
    }
    return value;
  }
}
