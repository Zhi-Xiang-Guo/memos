package dev.memos.adapters.postgres;

import dev.memos.audit.TraceAccessAudit;
import dev.memos.audit.TraceAccessAuditEvent;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcTraceAccessAudit implements TraceAccessAudit {
  private final JdbcTemplate jdbc;
  private final Supplier<UUID> identifiers;
  private final String policyVersion;

  public JdbcTraceAccessAudit(JdbcTemplate jdbc, Supplier<UUID> identifiers, String policyVersion) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    this.policyVersion = required(policyVersion, "policyVersion", 128);
  }

  @Override
  public void record(TraceAccessAuditEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    UUID auditEventId = Objects.requireNonNull(identifiers.get(), "generated audit ID");
    UUID targetId =
        UUID.nameUUIDFromBytes(
            ("retrieval-trace/" + event.traceId()).getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        INSERT INTO memos.audit_event (
            audit_event_id, tenant_id, user_id, agent_id, actor_type, actor_id,
            action, target_type, target_id, outcome, reason_code, policy_version,
            trace_id, created_at
        ) VALUES (?, ?, ?, ?, 'OPERATOR', ?, 'RETRIEVAL_TRACE_ACCESSED',
                  'RETRIEVAL_TRACE', ?, 'SUCCEEDED', 'ROLE_AUTHORIZED', ?, ?, ?)
        """,
        auditEventId,
        event.tenantId(),
        event.userId(),
        event.agentId(),
        event.subjectId(),
        targetId,
        policyVersion,
        event.traceId(),
        Timestamp.from(event.occurredAt()));
  }

  private static String required(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be non-blank and bounded");
    }
    return value;
  }
}
