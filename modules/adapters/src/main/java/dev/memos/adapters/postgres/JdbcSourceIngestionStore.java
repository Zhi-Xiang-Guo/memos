package dev.memos.adapters.postgres;

import dev.memos.ingestion.IngestionConflict;
import dev.memos.ingestion.IngestionDisposition;
import dev.memos.ingestion.MaterializationIntent;
import dev.memos.ingestion.MaterializationJobId;
import dev.memos.ingestion.SourceEvent;
import dev.memos.ingestion.SourceEventId;
import dev.memos.ingestion.SourceIngestionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSourceIngestionStore implements SourceIngestionStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcSourceIngestionStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
  }

  @Override
  public AcceptanceResult accept(SourceEvent sourceEvent, MaterializationIntent intent) {
    Objects.requireNonNull(sourceEvent, "sourceEvent must not be null");
    Objects.requireNonNull(intent, "intent must not be null");
    if (!sourceEvent.sourceEventId().equals(intent.sourceEventId())
        || !sourceEvent.scope().tenantId().equals(intent.tenantId())) {
      throw new IllegalArgumentException("source event and materialization intent must match");
    }
    return transactions.execute(status -> acceptInTransaction(sourceEvent, intent));
  }

  private AcceptanceResult acceptInTransaction(
      SourceEvent sourceEvent, MaterializationIntent intent) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO memos.source_event (
                source_event_id, tenant_id, source_id, user_id, agent_id, session_id,
                idempotency_key, actor_type, source_type, trust_level, occurred_at,
                received_at, payload, content_fingerprint, request_fingerprint,
                deletion_state, trace_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            sourceEvent.sourceEventId().value(),
            sourceEvent.scope().tenantId(),
            sourceEvent.sourceId(),
            sourceEvent.scope().userId(),
            sourceEvent.scope().agentId(),
            sourceEvent.sessionId(),
            sourceEvent.idempotencyKey(),
            sourceEvent.actorType().name(),
            sourceEvent.sourceType().name(),
            sourceEvent.trustLevel().name(),
            Timestamp.from(sourceEvent.occurredAt()),
            Timestamp.from(sourceEvent.receivedAt()),
            sourceEvent.canonicalPayload(),
            bytes(sourceEvent.contentFingerprint().hex()),
            bytes(sourceEvent.requestFingerprint().hex()),
            sourceEvent.deletionState().name(),
            sourceEvent.traceId(),
            Timestamp.from(sourceEvent.createdAt()));

    if (inserted == 1) {
      insertIntent(sourceEvent, intent);
      return accepted(sourceEvent, intent, IngestionDisposition.ACCEPTED);
    }
    return classifyExisting(sourceEvent);
  }

  private void insertIntent(SourceEvent sourceEvent, MaterializationIntent intent) {
    jdbc.update(
        """
        INSERT INTO memos.outbox_job (
            job_id, tenant_id, job_type, aggregate_type, aggregate_id, source_event_id,
            semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
            next_attempt_at, payload_reference, replay_count, trace_id, created_at, updated_at
        ) VALUES (?, ?, 'MATERIALIZE_SOURCE', 'SOURCE_EVENT', ?, ?, ?, ?, ?, 'PENDING',
                  0, ?, ?, ?, 0, ?, ?, ?)
        """,
        intent.jobId().value(),
        intent.tenantId(),
        intent.sourceEventId().value(),
        intent.sourceEventId().value(),
        intent.semanticJobKey(),
        intent.policyVersion(),
        intent.modelVersion(),
        intent.maxAttempts(),
        Timestamp.from(intent.nextAttemptAt()),
        intent.sourceEventId().value(),
        intent.traceId(),
        Timestamp.from(intent.createdAt()),
        Timestamp.from(intent.createdAt()));
  }

  private AcceptanceResult classifyExisting(SourceEvent proposed) {
    ExistingAcceptance byIdempotency =
        findExisting(
            "se.idempotency_key = ?", proposed.scope().tenantId(), proposed.idempotencyKey());
    if (byIdempotency != null) {
      return sameRequest(proposed, byIdempotency)
          ? accepted(byIdempotency, IngestionDisposition.IDEMPOTENT_REPLAY)
          : new Conflict(IngestionConflict.IDEMPOTENCY_KEY_REUSED);
    }

    ExistingAcceptance bySource =
        findExisting("se.source_id = ?", proposed.scope().tenantId(), proposed.sourceId());
    if (bySource != null) {
      return sameRequest(proposed, bySource)
          ? accepted(bySource, IngestionDisposition.SOURCE_REPLAY)
          : new Conflict(IngestionConflict.SOURCE_ID_REUSED);
    }
    throw new IllegalStateException("source insert lost without a conflicting record");
  }

  private ExistingAcceptance findExisting(String predicate, String tenantId, String value) {
    List<ExistingAcceptance> matches =
        jdbc.query(
            """
            SELECT se.source_event_id, se.source_id, se.request_fingerprint, se.received_at,
                   job.job_id
            FROM memos.source_event se
            JOIN memos.outbox_job job
              ON job.tenant_id = se.tenant_id
             AND job.source_event_id = se.source_event_id
             AND job.job_type = 'MATERIALIZE_SOURCE'
            WHERE se.tenant_id = ? AND
            """
                + predicate,
            (result, row) ->
                new ExistingAcceptance(
                    result.getObject("source_event_id", UUID.class),
                    result.getString("source_id"),
                    result.getBytes("request_fingerprint"),
                    result.getTimestamp("received_at").toInstant(),
                    result.getObject("job_id", UUID.class)),
            tenantId,
            value);
    if (matches.size() > 1) {
      throw new IllegalStateException("ingestion uniqueness invariant violated");
    }
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static boolean sameRequest(SourceEvent proposed, ExistingAcceptance existing) {
    return Arrays.equals(bytes(proposed.requestFingerprint().hex()), existing.requestFingerprint());
  }

  private static Accepted accepted(
      SourceEvent sourceEvent, MaterializationIntent intent, IngestionDisposition disposition) {
    return new Accepted(
        sourceEvent.sourceEventId(),
        sourceEvent.sourceId(),
        intent.jobId(),
        disposition,
        sourceEvent.receivedAt());
  }

  private static Accepted accepted(ExistingAcceptance existing, IngestionDisposition disposition) {
    return new Accepted(
        new SourceEventId(existing.sourceEventId()),
        existing.sourceId(),
        new MaterializationJobId(existing.jobId()),
        disposition,
        existing.acceptedAt());
  }

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private record ExistingAcceptance(
      UUID sourceEventId,
      String sourceId,
      byte[] requestFingerprint,
      Instant acceptedAt,
      UUID jobId) {}
}
