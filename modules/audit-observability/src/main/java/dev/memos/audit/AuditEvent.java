package dev.memos.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AuditEvent(String type, Instant occurredAt, Map<String, String> attributes) {
  public AuditEvent {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    attributes = Map.copyOf(attributes);
  }
}
