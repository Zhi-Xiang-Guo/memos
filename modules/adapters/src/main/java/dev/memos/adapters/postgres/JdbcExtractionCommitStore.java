package dev.memos.adapters.postgres;

import dev.memos.domain.candidate.CandidateRelation;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.ProposedTimeRange;
import dev.memos.materialization.CandidateCommitRecord;
import dev.memos.materialization.CandidateQuarantineRecord;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.CommitExtractionSuccess;
import dev.memos.materialization.CommitInvalidExtraction;
import dev.memos.materialization.CommitSkippedExtraction;
import dev.memos.materialization.DownstreamMaterializationIntent;
import dev.memos.materialization.ExtractionAttemptStartResult;
import dev.memos.materialization.ExtractionCommitResult;
import dev.memos.materialization.ExtractionCommitStore;
import dev.memos.materialization.ProviderCallMetadata;
import dev.memos.materialization.RecordTransientExtractionFailure;
import dev.memos.materialization.StartExtractionAttempt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL authority for extraction attempts and their lease-fenced terminal effects. */
public final class JdbcExtractionCommitStore implements ExtractionCommitStore {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcExtractionCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
  }

  @Override
  public ExtractionAttemptStartResult startAttempt(StartExtractionAttempt command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(status -> startAttemptInTransaction(command));
  }

  private ExtractionAttemptStartResult startAttemptInTransaction(StartExtractionAttempt command) {
    if (!holdsCurrentLease(lockJob(command.job()), command.job())) {
      return ExtractionAttemptStartResult.LEASE_LOST;
    }
    int inserted =
        jdbc.update(
            """
            INSERT INTO memos.extraction_attempt (
                attempt_id, tenant_id, job_id, job_attempt, lease_token, provider,
                model_version, prompt_version, schema_version, policy_version, state,
                started_at, model_calls
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', ?, 0)
            ON CONFLICT DO NOTHING
            """,
            command.attemptId().value(),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().attempt(),
            command.job().leaseToken().value(),
            command.providerIdentity().provider(),
            command.providerIdentity().modelVersion(),
            command.providerIdentity().promptVersion(),
            command.providerIdentity().schemaVersion(),
            command.policyVersion(),
            Timestamp.from(command.startedAt()));
    if (inserted == 1) {
      return ExtractionAttemptStartResult.STARTED;
    }
    Integer matches =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM memos.extraction_attempt
             WHERE attempt_id = ? AND tenant_id = ? AND job_id = ? AND job_attempt = ?
               AND lease_token = ? AND provider = ? AND model_version = ?
               AND prompt_version = ? AND schema_version = ? AND policy_version = ?
            """,
            Integer.class,
            command.attemptId().value(),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().attempt(),
            command.job().leaseToken().value(),
            command.providerIdentity().provider(),
            command.providerIdentity().modelVersion(),
            command.providerIdentity().promptVersion(),
            command.providerIdentity().schemaVersion(),
            command.policyVersion());
    if (matches != null && matches == 1) {
      return ExtractionAttemptStartResult.ALREADY_STARTED;
    }
    throw new IllegalStateException("extraction attempt identity conflict");
  }

  @Override
  public ExtractionCommitResult commitSuccess(CommitExtractionSuccess command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(status -> commitSuccessInTransaction(command));
  }

  private ExtractionCommitResult commitSuccessInTransaction(CommitExtractionSuccess command) {
    LockedJob locked = lockJob(command.job());
    if ("SUCCEEDED".equals(locked.state())) {
      return successAlreadyCommitted(command)
          ? ExtractionCommitResult.ALREADY_COMMITTED
          : ExtractionCommitResult.LEASE_LOST;
    }
    if (!holdsCurrentLease(locked, command.job())) {
      return ExtractionCommitResult.LEASE_LOST;
    }

    ProviderCallMetadata metadata = command.providerMetadata();
    int attemptUpdated =
        jdbc.update(
            """
            UPDATE memos.extraction_attempt
               SET state = 'SUCCEEDED', provider_call_id = ?, finished_at = ?,
                   input_tokens = ?, output_tokens = ?, model_calls = 1, duration_ms = ?,
                   finish_reason = 'VALID_SCHEMA', error_class = NULL
             WHERE attempt_id = ? AND tenant_id = ? AND job_id = ? AND lease_token = ?
               AND state = 'STARTED' AND provider = ? AND model_version = ?
               AND prompt_version = ? AND schema_version = ? AND policy_version = ?
            """,
            metadata.providerCallId(),
            Timestamp.from(command.committedAt()),
            metadata.tokenUsage().inputTokens(),
            metadata.tokenUsage().outputTokens(),
            metadata.latency().toMillis(),
            command.attemptId().value(),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().leaseToken().value(),
            metadata.provider(),
            metadata.modelVersion(),
            metadata.promptVersion(),
            metadata.schemaVersion(),
            command.policyVersion());
    if (attemptUpdated != 1) {
      throw new IllegalStateException("started extraction attempt does not match commit metadata");
    }

    Counts counts = Counts.from(command.candidates());
    jdbc.update(
        """
        INSERT INTO memos.extraction_run (
            run_id, tenant_id, source_event_id, extraction_job_id, semantic_run_key,
            attempt_id, provider, model_version, prompt_version, schema_version,
            policy_version, candidate_count, remember_count, ignore_count, review_count,
            created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        command.runId().value(),
        command.job().scope().tenantId(),
        command.job().sourceEventId(),
        command.job().jobId().value(),
        command.semanticRunKey(),
        command.attemptId().value(),
        metadata.provider(),
        metadata.modelVersion(),
        metadata.promptVersion(),
        metadata.schemaVersion(),
        command.policyVersion(),
        counts.total(),
        counts.remember(),
        counts.ignore(),
        counts.review(),
        Timestamp.from(command.committedAt()));

    for (CandidateCommitRecord candidate : command.candidates()) {
      insertCandidate(command, candidate);
      insertPolicyDecision(command, candidate);
    }
    for (CandidateQuarantineRecord quarantine : command.quarantines()) {
      insertQuarantine(command, quarantine);
    }
    if (command.downstreamIntent() != null) {
      insertDownstreamIntent(command, command.downstreamIntent());
    }
    completeSourceJob(command);
    return ExtractionCommitResult.COMMITTED;
  }

  @Override
  public ExtractionCommitResult commitInvalidSchema(CommitInvalidExtraction command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(status -> commitInvalidInTransaction(command));
  }

  private ExtractionCommitResult commitInvalidInTransaction(CommitInvalidExtraction command) {
    LockedJob locked = lockJob(command.job());
    if ("DEAD".equals(locked.state())) {
      Integer existing =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM memos.memory_quarantine
               WHERE tenant_id = ? AND quarantine_id = ? AND attempt_id = ? AND job_id = ?
                 AND reason_code = ? AND error_path = ?
              """,
              Integer.class,
              command.job().scope().tenantId(),
              command.quarantineId().value(),
              command.attemptId().value(),
              command.job().jobId().value(),
              command.decodingError().name(),
              command.errorPath());
      return existing != null && existing == 1
          ? ExtractionCommitResult.ALREADY_COMMITTED
          : ExtractionCommitResult.LEASE_LOST;
    }
    if (!holdsCurrentLease(locked, command.job())) {
      return ExtractionCommitResult.LEASE_LOST;
    }
    ProviderCallMetadata metadata = command.providerMetadata();
    int updated =
        jdbc.update(
            """
            UPDATE memos.extraction_attempt
               SET state = 'INVALID_SCHEMA', provider_call_id = ?, finished_at = ?,
                   input_tokens = ?, output_tokens = ?, model_calls = 1, duration_ms = ?,
                   finish_reason = NULL, error_class = ?
             WHERE attempt_id = ? AND tenant_id = ? AND job_id = ? AND lease_token = ?
               AND state = 'STARTED'
            """,
            metadata.providerCallId(),
            Timestamp.from(command.committedAt()),
            metadata.tokenUsage().inputTokens(),
            metadata.tokenUsage().outputTokens(),
            metadata.latency().toMillis(),
            command.decodingError().name(),
            command.attemptId().value(),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().leaseToken().value());
    if (updated != 1) {
      throw new IllegalStateException("started extraction attempt is missing");
    }
    jdbc.update(
        """
        INSERT INTO memos.memory_quarantine (
            quarantine_id, tenant_id, run_id, attempt_id, source_event_id, job_id,
            candidate_id, ordinal, reason_code, error_path, state, created_at
        ) VALUES (?, ?, NULL, ?, ?, ?, NULL, NULL, ?, ?, 'OPEN', ?)
        """,
        command.quarantineId().value(),
        command.job().scope().tenantId(),
        command.attemptId().value(),
        command.job().sourceEventId(),
        command.job().jobId().value(),
        command.decodingError().name(),
        command.errorPath(),
        Timestamp.from(command.committedAt()));
    int jobUpdated =
        jdbc.update(
            """
            UPDATE memos.outbox_job
               SET state = 'DEAD', next_attempt_at = NULL, lease_owner = NULL,
                   lease_token = NULL, lease_expires_at = NULL, error_class = ?,
                   completed_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = ? AND job_id = ? AND state = 'CLAIMED'
               AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            """,
            command.decodingError().name(),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().leaseOwner().value(),
            command.job().leaseToken().value());
    if (jobUpdated != 1) {
      throw new IllegalStateException("lease changed while committing invalid extraction");
    }
    return ExtractionCommitResult.COMMITTED;
  }

  @Override
  public ExtractionCommitResult commitSkipped(CommitSkippedExtraction command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(status -> commitSkippedInTransaction(command));
  }

  private ExtractionCommitResult commitSkippedInTransaction(CommitSkippedExtraction command) {
    LockedJob locked = lockJob(command.job());
    String outcome = "SKIPPED_" + command.reason().name();
    if ("SUCCEEDED".equals(locked.state())) {
      Integer existing =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM memos.materialization_result
               WHERE tenant_id = ? AND job_id = ? AND source_event_id = ? AND outcome = ?
              """,
              Integer.class,
              command.job().scope().tenantId(),
              command.job().jobId().value(),
              command.job().sourceEventId(),
              outcome);
      return existing != null && existing == 1
          ? ExtractionCommitResult.ALREADY_COMMITTED
          : ExtractionCommitResult.LEASE_LOST;
    }
    if (!holdsCurrentLease(locked, command.job())) {
      return ExtractionCommitResult.LEASE_LOST;
    }
    List<Completion> completions =
        jdbc.query(
            """
            UPDATE memos.outbox_job
               SET state = 'SUCCEEDED', next_attempt_at = NULL, lease_owner = NULL,
                   lease_token = NULL, lease_expires_at = NULL, error_class = NULL,
                   completed_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = ? AND job_id = ? AND state = 'CLAIMED'
               AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            RETURNING semantic_job_key, completed_at
            """,
            (result, row) ->
                new Completion(
                    result.getString("semantic_job_key"), instant(result, "completed_at")),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().leaseOwner().value(),
            command.job().leaseToken().value());
    if (completions.size() != 1) {
      throw new IllegalStateException("lease changed while skipping extraction");
    }
    Completion completion = completions.getFirst();
    jdbc.update(
        """
        INSERT INTO memos.materialization_result (
            tenant_id, semantic_job_key, job_id, source_event_id, outcome,
            handler_version, completed_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        command.job().scope().tenantId(),
        completion.semanticJobKey(),
        command.job().jobId().value(),
        command.job().sourceEventId(),
        outcome,
        command.job().policyVersion(),
        Timestamp.from(completion.completedAt()),
        Timestamp.from(completion.completedAt()));
    return ExtractionCommitResult.COMMITTED;
  }

  @Override
  public void recordTransientFailure(RecordTransientExtractionFailure command) {
    Objects.requireNonNull(command, "command must not be null");
    transactions.executeWithoutResult(status -> recordTransientInTransaction(command));
  }

  private void recordTransientInTransaction(RecordTransientExtractionFailure command) {
    if (!holdsCurrentLease(lockJob(command.job()), command.job())) {
      return;
    }
    ProviderCallMetadata metadata = command.providerMetadata();
    jdbc.update(
        """
        UPDATE memos.extraction_attempt
           SET state = 'TRANSIENT_FAILURE', provider_call_id = ?, finished_at = ?,
               input_tokens = ?, output_tokens = ?, model_calls = ?, duration_ms = ?,
               finish_reason = NULL, error_class = ?
         WHERE attempt_id = ? AND tenant_id = ? AND job_id = ? AND lease_token = ?
           AND state = 'STARTED'
        """,
        metadata == null ? null : metadata.providerCallId(),
        Timestamp.from(command.recordedAt()),
        metadata == null ? null : metadata.tokenUsage().inputTokens(),
        metadata == null ? null : metadata.tokenUsage().outputTokens(),
        metadata == null ? 0 : 1,
        metadata == null ? null : metadata.latency().toMillis(),
        command.errorClass().value(),
        command.attemptId().value(),
        command.job().scope().tenantId(),
        command.job().jobId().value(),
        command.job().leaseToken().value());
  }

  private void insertCandidate(CommitExtractionSuccess command, CandidateCommitRecord evaluated) {
    MemoryCandidateProposal proposal = evaluated.content();
    String eventTime = proposal == null ? null : jsonTime(proposal.eventTime());
    String validInterval = proposal == null ? null : jsonTime(proposal.validInterval());
    String relations = proposal == null ? null : jsonRelations(proposal.candidateRelations());
    String sensitivity =
        proposal == null
            ? "{}"
            : postgresArray(proposal.sensitivity().stream().map(Enum::name).sorted().toList());
    byte[] fingerprint =
        proposal == null
            ? null
            : sha256(
                proposal.subject().kind().name()
                    + "\u0000"
                    + Objects.toString(proposal.subject().label(), "")
                    + "\u0000"
                    + proposal.predicate()
                    + "\u0000"
                    + proposal.value().canonicalJson()
                    + "\u0000"
                    + proposal.normalizedContent());
    jdbc.update(
        """
        INSERT INTO memos.memory_candidate (
            candidate_id, tenant_id, run_id, source_event_id, ordinal, schema_version,
            proposed_decision, subject_kind, subject_label, predicate, value_json,
            normalized_content, memory_type, event_time_json, valid_interval_json,
            importance, confidence, source_type, source_trust, sensitivity,
            relation_hints_json, content_fingerprint, content_state, created_at
        )
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb,
               ?, ?, source.source_type, source.trust_level, ?::text[], ?::jsonb, ?, ?, ?
          FROM memos.source_event source
         WHERE source.tenant_id = ? AND source.source_event_id = ?
        """,
        evaluated.candidateId().value(),
        command.job().scope().tenantId(),
        command.runId().value(),
        command.job().sourceEventId(),
        evaluated.ordinal(),
        command.providerMetadata().schemaVersion(),
        proposal == null ? null : proposal.proposedDecision().name(),
        proposal == null ? null : proposal.subject().kind().name(),
        proposal == null ? null : proposal.subject().label(),
        proposal == null ? null : proposal.predicate(),
        proposal == null ? null : proposal.value().canonicalJson(),
        proposal == null ? null : proposal.normalizedContent(),
        proposal == null ? null : proposal.memoryType().name(),
        eventTime,
        validInterval,
        proposal == null ? null : proposal.importance(),
        proposal == null ? null : proposal.confidence(),
        sensitivity,
        relations,
        fingerprint,
        evaluated.contentState().name(),
        Timestamp.from(command.committedAt()),
        command.job().scope().tenantId(),
        command.job().sourceEventId());
  }

  private void insertPolicyDecision(
      CommitExtractionSuccess command, CandidateCommitRecord candidate) {
    String identity =
        command.job().scope().tenantId()
            + "/"
            + candidate.candidateId().value()
            + "/"
            + command.policyVersion();
    jdbc.update(
        """
        INSERT INTO memos.candidate_policy_decision (
            decision_id, tenant_id, run_id, candidate_id, ordinal, decision,
            sensitivity_action, effective_scope, reason_codes, policy_version, decided_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TENANT_USER_AGENT', ?::text[], ?, ?)
        """,
        deterministicUuid("policy/" + identity),
        command.job().scope().tenantId(),
        command.runId().value(),
        candidate.candidateId().value(),
        candidate.ordinal(),
        candidate.policyDecision().decision().name(),
        candidate.policyDecision().sensitivityAction().name(),
        postgresArray(candidate.policyDecision().reasons().stream().map(Enum::name).toList()),
        candidate.policyDecision().policyVersion(),
        Timestamp.from(command.committedAt()));
  }

  private void insertQuarantine(
      CommitExtractionSuccess command, CandidateQuarantineRecord quarantine) {
    jdbc.update(
        """
        INSERT INTO memos.memory_quarantine (
            quarantine_id, tenant_id, run_id, attempt_id, source_event_id, job_id,
            candidate_id, ordinal, reason_code, error_path, state, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'OPEN', ?)
        """,
        quarantine.quarantineId().value(),
        command.job().scope().tenantId(),
        command.runId().value(),
        command.attemptId().value(),
        command.job().sourceEventId(),
        command.job().jobId().value(),
        quarantine.candidateId().value(),
        quarantine.ordinal(),
        quarantine.reason().name(),
        Timestamp.from(command.committedAt()));
  }

  private void insertDownstreamIntent(
      CommitExtractionSuccess command, DownstreamMaterializationIntent intent) {
    UUID downstreamJobId =
        deterministicUuid(
            "downstream/"
                + command.job().scope().tenantId()
                + "/"
                + command.runId().value()
                + "/"
                + intent.semanticJobKey().value());
    int inserted =
        jdbc.update(
            """
            INSERT INTO memos.outbox_job (
                job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
                semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
                replay_count, next_attempt_at, payload_reference, trace_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'EXTRACTION_RUN', ?, ?, ?, ?, 'PENDING', 0, ?, 0,
                      clock_timestamp(), ?, ?, clock_timestamp(), clock_timestamp())
            ON CONFLICT (tenant_id, semantic_job_key) DO NOTHING
            """,
            downstreamJobId,
            command.job().scope().tenantId(),
            command.job().sourceEventId(),
            intent.jobType().name(),
            command.runId().value(),
            intent.semanticJobKey().value(),
            command.policyVersion(),
            command.providerMetadata().modelVersion(),
            command.job().maxAttempts(),
            command.job().sourceEventId(),
            command.job().traceId());
    if (inserted == 0) {
      Integer matching =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM memos.outbox_job
               WHERE tenant_id = ? AND semantic_job_key = ? AND job_id = ?
                 AND job_type = ? AND aggregate_type = 'EXTRACTION_RUN' AND aggregate_id = ?
                 AND source_event_id = ?
              """,
              Integer.class,
              command.job().scope().tenantId(),
              intent.semanticJobKey().value(),
              downstreamJobId,
              intent.jobType().name(),
              command.runId().value(),
              command.job().sourceEventId());
      if (matching == null || matching != 1) {
        throw new IllegalStateException("downstream materialization identity conflict");
      }
    }
  }

  private void completeSourceJob(CommitExtractionSuccess command) {
    List<Completion> completions =
        jdbc.query(
            """
            UPDATE memos.outbox_job
               SET state = 'SUCCEEDED', next_attempt_at = NULL, lease_owner = NULL,
                   lease_token = NULL, lease_expires_at = NULL, error_class = NULL,
                   completed_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = ? AND job_id = ? AND state = 'CLAIMED'
               AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            RETURNING semantic_job_key, completed_at
            """,
            (result, row) ->
                new Completion(
                    result.getString("semantic_job_key"), instant(result, "completed_at")),
            command.job().scope().tenantId(),
            command.job().jobId().value(),
            command.job().leaseOwner().value(),
            command.job().leaseToken().value());
    if (completions.size() != 1) {
      throw new IllegalStateException("lease changed while committing extraction");
    }
    Completion completion = completions.getFirst();
    jdbc.update(
        """
        INSERT INTO memos.materialization_result (
            tenant_id, semantic_job_key, job_id, source_event_id, outcome,
            handler_version, completed_at, created_at
        ) VALUES (?, ?, ?, ?, 'EXTRACTED', ?, ?, ?)
        """,
        command.job().scope().tenantId(),
        completion.semanticJobKey(),
        command.job().jobId().value(),
        command.job().sourceEventId(),
        command.providerMetadata().schemaVersion(),
        Timestamp.from(completion.completedAt()),
        Timestamp.from(completion.completedAt()));
  }

  private boolean successAlreadyCommitted(CommitExtractionSuccess command) {
    Integer matches =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM memos.extraction_run run
            JOIN memos.materialization_result result
              ON result.tenant_id = run.tenant_id AND result.job_id = run.extraction_job_id
             WHERE run.tenant_id = ? AND run.run_id = ? AND run.extraction_job_id = ?
               AND run.attempt_id = ? AND run.semantic_run_key = ?
               AND result.source_event_id = ? AND result.outcome = 'EXTRACTED'
            """,
            Integer.class,
            command.job().scope().tenantId(),
            command.runId().value(),
            command.job().jobId().value(),
            command.attemptId().value(),
            command.semanticRunKey(),
            command.job().sourceEventId());
    return matches != null && matches == 1;
  }

  private LockedJob lockJob(ClaimedJob job) {
    List<LockedJob> rows =
        jdbc.query(
            """
            SELECT state, lease_owner, lease_token,
                   lease_expires_at > clock_timestamp() AS lease_valid
              FROM memos.outbox_job
             WHERE tenant_id = ? AND job_id = ? AND source_event_id = ?
             FOR UPDATE
            """,
            (result, row) ->
                new LockedJob(
                    result.getString("state"),
                    result.getString("lease_owner"),
                    result.getObject("lease_token", UUID.class),
                    result.getBoolean("lease_valid")),
            job.scope().tenantId(),
            job.jobId().value(),
            job.sourceEventId());
    return rows.isEmpty() ? LockedJob.missing() : rows.getFirst();
  }

  private static boolean holdsCurrentLease(LockedJob locked, ClaimedJob job) {
    return "CLAIMED".equals(locked.state())
        && job.leaseOwner().value().equals(locked.leaseOwner())
        && job.leaseToken().value().equals(locked.leaseToken())
        && locked.leaseValid();
  }

  private static String jsonTime(ProposedTimeRange time) {
    if (time == null) {
      return null;
    }
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("original_text", time.originalText());
    value.put(
        "start_inclusive", time.startInclusive() == null ? null : time.startInclusive().toString());
    value.put("end_exclusive", time.endExclusive() == null ? null : time.endExclusive().toString());
    value.put("precision", time.precision().name());
    value.put("confidence", time.confidence());
    return json(value);
  }

  private static String jsonRelations(List<CandidateRelation> relations) {
    return json(
        relations.stream()
            .map(
                relation -> {
                  Map<String, Object> value = new LinkedHashMap<>();
                  value.put("type", relation.type().name());
                  value.put("target_subject", relation.targetSubject());
                  value.put("target_predicate", relation.targetPredicate());
                  return value;
                })
            .toList());
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("candidate metadata is not JSON serializable", exception);
    }
  }

  private static String postgresArray(List<String> values) {
    return "{" + String.join(",", values) + "}";
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static UUID deterministicUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return result.getObject(column, java.time.OffsetDateTime.class).toInstant();
  }

  private record LockedJob(String state, String leaseOwner, UUID leaseToken, boolean leaseValid) {
    private static LockedJob missing() {
      return new LockedJob("MISSING", null, null, false);
    }
  }

  private record Completion(String semanticJobKey, Instant completedAt) {}

  private record Counts(int total, int remember, int ignore, int review) {
    private static Counts from(List<CandidateCommitRecord> candidates) {
      int remember = 0;
      int ignore = 0;
      int review = 0;
      for (CandidateCommitRecord candidate : candidates) {
        switch (candidate.policyDecision().decision()) {
          case REMEMBER -> remember++;
          case IGNORE -> ignore++;
          case REVIEW -> review++;
        }
      }
      return new Counts(candidates.size(), remember, ignore, review);
    }
  }
}
