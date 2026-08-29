package dev.memos.adapters.postgres;

import dev.memos.governance.MemoryScope;
import dev.memos.materialization.ClaimRequest;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.FencedUpdateResult;
import dev.memos.materialization.JobErrorClass;
import dev.memos.materialization.JobId;
import dev.memos.materialization.JobState;
import dev.memos.materialization.JobType;
import dev.memos.materialization.LeaseFence;
import dev.memos.materialization.LeaseToken;
import dev.memos.materialization.MaterializationJob;
import dev.memos.materialization.MaterializationJobStore;
import dev.memos.materialization.ReplayResult;
import dev.memos.materialization.SemanticJobKey;
import dev.memos.materialization.WorkerId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcMaterializationJobStore implements MaterializationJobStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcMaterializationJobStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
  }

  @Override
  public int deadLetterExpiredExhaustedJobs(Instant ignoredApplicationTime) {
    return jdbc.update(
        """
        UPDATE memos.outbox_job
           SET state = 'DEAD',
               lease_owner = NULL,
               lease_token = NULL,
               lease_expires_at = NULL,
               error_class = 'LEASE_EXPIRED_ATTEMPTS_EXHAUSTED',
               completed_at = clock_timestamp(),
               updated_at = clock_timestamp()
         WHERE state = 'CLAIMED'
           AND lease_expires_at <= clock_timestamp()
           AND attempt >= max_attempts
        """);
  }

  @Override
  public List<ClaimedJob> claim(ClaimRequest request) {
    long leaseMilliseconds = request.leaseDuration().toMillis();
    return transactions.execute(
        status ->
            jdbc.query(
                """
                WITH candidates AS (
                    SELECT job.job_id, source.user_id, source.agent_id
                      FROM memos.outbox_job job
                      JOIN memos.source_event source
                        ON source.tenant_id = job.tenant_id
                       AND source.source_event_id = job.source_event_id
                     WHERE (
                         (
                             job.state IN ('PENDING', 'RETRY_WAIT')
                             AND job.next_attempt_at <= clock_timestamp()
                         ) OR (
                             job.state = 'CLAIMED'
                             AND job.lease_expires_at <= clock_timestamp()
                             AND job.attempt < job.max_attempts
                         )
                     ) AND job.job_type = ANY (string_to_array(?, ','))
                       AND source.deletion_state = 'ACTIVE'
                     ORDER BY COALESCE(job.next_attempt_at, job.lease_expires_at),
                              job.created_at, job.job_id
                     FOR UPDATE OF job SKIP LOCKED
                     LIMIT ?
                )
                UPDATE memos.outbox_job job
                   SET state = 'CLAIMED',
                       attempt = job.attempt + 1,
                       next_attempt_at = NULL,
                       lease_owner = ?,
                       lease_token = gen_random_uuid(),
                       lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                       error_class = CASE
                           WHEN job.state = 'CLAIMED' THEN 'LEASE_EXPIRED_RECLAIMED'
                           ELSE NULL
                       END,
                       updated_at = clock_timestamp()
                  FROM candidates candidate
                 WHERE job.job_id = candidate.job_id
                RETURNING job.job_id, job.job_type, job.tenant_id,
                          candidate.user_id, candidate.agent_id,
                          job.source_event_id, job.semantic_job_key, job.policy_version,
                          job.model_version, job.attempt, job.max_attempts, job.lease_owner,
                          job.lease_token, job.lease_expires_at, job.trace_id
                """,
                (result, row) -> mapClaimed(result),
                request.supportedJobTypes().stream()
                    .map(Enum::name)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(",")),
                request.batchSize(),
                request.workerId().value(),
                leaseMilliseconds));
  }

  @Override
  public FencedUpdateResult markSucceeded(LeaseFence fence, Instant ignoredCompletedAt) {
    return transactions.execute(status -> markSucceededInTransaction(fence));
  }

  private FencedUpdateResult markSucceededInTransaction(LeaseFence fence) {
    List<CompletionIdentity> completed =
        jdbc.query(
            """
            UPDATE memos.outbox_job
               SET state = 'SUCCEEDED',
                   lease_owner = NULL,
                   lease_token = NULL,
                   lease_expires_at = NULL,
                   error_class = NULL,
                   completed_at = clock_timestamp(),
                   updated_at = clock_timestamp()
             WHERE job_id = ?
               AND state = 'CLAIMED'
               AND lease_owner = ?
               AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            RETURNING tenant_id, job_id, source_event_id, semantic_job_key, model_version,
                      completed_at
            """,
            (result, row) ->
                new CompletionIdentity(
                    result.getString("tenant_id"),
                    result.getObject("job_id", UUID.class),
                    result.getObject("source_event_id", UUID.class),
                    result.getString("semantic_job_key"),
                    result.getString("model_version"),
                    instant(result, "completed_at")),
            fence.jobId().value(),
            fence.leaseOwner().value(),
            fence.leaseToken().value());
    if (completed.isEmpty()) {
      return FencedUpdateResult.LEASE_LOST;
    }
    CompletionIdentity identity = completed.getFirst();
    int inserted =
        jdbc.update(
            """
            INSERT INTO memos.materialization_result (
                tenant_id, semantic_job_key, job_id, source_event_id, outcome,
                handler_version, completed_at, created_at
            ) VALUES (?, ?, ?, ?, 'MATERIALIZED', ?, ?, ?)
            ON CONFLICT (tenant_id, semantic_job_key) DO NOTHING
            """,
            identity.tenantId(),
            identity.semanticJobKey(),
            identity.jobId(),
            identity.sourceEventId(),
            identity.handlerVersion(),
            Timestamp.from(identity.completedAt()),
            Timestamp.from(identity.completedAt()));
    if (inserted == 0) {
      Integer matching =
          jdbc.queryForObject(
              """
              SELECT count(*)
                FROM memos.materialization_result
               WHERE tenant_id = ? AND semantic_job_key = ?
                 AND job_id = ? AND source_event_id = ? AND handler_version = ?
              """,
              Integer.class,
              identity.tenantId(),
              identity.semanticJobKey(),
              identity.jobId(),
              identity.sourceEventId(),
              identity.handlerVersion());
      if (matching == null || matching != 1) {
        throw new IllegalStateException("materialization effect identity conflict");
      }
    }
    return FencedUpdateResult.UPDATED;
  }

  @Override
  public FencedUpdateResult scheduleRetry(
      LeaseFence fence, JobErrorClass errorClass, Instant nextAttemptAt, Instant ignoredUpdatedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE memos.outbox_job
               SET state = 'RETRY_WAIT', next_attempt_at = ?,
                   lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                   error_class = ?, completed_at = NULL, updated_at = clock_timestamp()
             WHERE job_id = ? AND state = 'CLAIMED' AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            """,
            Timestamp.from(nextAttemptAt),
            errorClass.value(),
            fence.jobId().value(),
            fence.leaseOwner().value(),
            fence.leaseToken().value());
    return fencedResult(updated);
  }

  @Override
  public FencedUpdateResult markDead(
      LeaseFence fence, JobErrorClass errorClass, Instant ignoredCompletedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE memos.outbox_job
               SET state = 'DEAD', next_attempt_at = NULL,
                   lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                   error_class = ?, completed_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE job_id = ? AND state = 'CLAIMED' AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            """,
            errorClass.value(),
            fence.jobId().value(),
            fence.leaseOwner().value(),
            fence.leaseToken().value());
    return fencedResult(updated);
  }

  @Override
  public Optional<MaterializationJob> find(MemoryScope scope, JobId jobId) {
    List<MaterializationJob> jobs =
        jdbc.query(
            """
            SELECT job.*, source.user_id, source.agent_id
              FROM memos.outbox_job job
              JOIN memos.source_event source
                ON source.tenant_id = job.tenant_id
               AND source.source_event_id = job.source_event_id
             WHERE job.job_id = ? AND job.tenant_id = ?
               AND source.user_id = ? AND source.agent_id = ?
            """,
            (result, row) -> mapJob(result),
            jobId.value(),
            scope.tenantId(),
            scope.userId(),
            scope.agentId());
    if (jobs.size() > 1) {
      throw new IllegalStateException("job identity invariant violated");
    }
    return jobs.stream().findFirst();
  }

  @Override
  public ReplayResult replay(MemoryScope scope, JobId jobId, Instant ignoredReplayedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE memos.outbox_job job
               SET state = 'PENDING', attempt = 0, next_attempt_at = clock_timestamp(),
                   lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                   error_class = NULL, replay_count = replay_count + 1,
                   completed_at = NULL, updated_at = clock_timestamp()
             WHERE job.job_id = ? AND job.tenant_id = ?
               AND (
                   job.state IN ('DEAD', 'RETRY_WAIT')
                   OR (job.state = 'CLAIMED' AND job.lease_expires_at <= clock_timestamp())
               )
               AND job.error_class IS DISTINCT FROM 'GOVERNED_ERASURE'
               AND EXISTS (
                   SELECT 1 FROM memos.source_event source
                    WHERE source.tenant_id = job.tenant_id
                      AND source.source_event_id = job.source_event_id
                      AND source.user_id = ? AND source.agent_id = ?
                      AND source.deletion_state = 'ACTIVE'
               )
            """,
            jobId.value(),
            scope.tenantId(),
            scope.userId(),
            scope.agentId());
    if (updated == 1) {
      return ReplayResult.REPLAYED;
    }
    return find(scope, jobId).isPresent() ? ReplayResult.NOT_REPLAYABLE : ReplayResult.NOT_FOUND;
  }

  private static ClaimedJob mapClaimed(ResultSet result) throws SQLException {
    return new ClaimedJob(
        new JobId(result.getObject("job_id", UUID.class)),
        JobType.valueOf(result.getString("job_type")),
        new MemoryScope(
            result.getString("tenant_id"),
            result.getString("user_id"),
            result.getString("agent_id")),
        result.getObject("source_event_id", UUID.class),
        new SemanticJobKey(result.getString("semantic_job_key")),
        result.getString("policy_version"),
        result.getString("model_version"),
        result.getInt("attempt"),
        result.getInt("max_attempts"),
        new WorkerId(result.getString("lease_owner")),
        new LeaseToken(result.getObject("lease_token", UUID.class)),
        instant(result, "lease_expires_at"),
        result.getString("trace_id"));
  }

  private static MaterializationJob mapJob(ResultSet result) throws SQLException {
    String leaseOwner = result.getString("lease_owner");
    UUID leaseToken = result.getObject("lease_token", UUID.class);
    String errorClass = result.getString("error_class");
    return new MaterializationJob(
        new JobId(result.getObject("job_id", UUID.class)),
        JobType.valueOf(result.getString("job_type")),
        new MemoryScope(
            result.getString("tenant_id"),
            result.getString("user_id"),
            result.getString("agent_id")),
        result.getObject("source_event_id", UUID.class),
        new SemanticJobKey(result.getString("semantic_job_key")),
        result.getString("policy_version"),
        result.getString("model_version"),
        JobState.valueOf(result.getString("state")),
        result.getInt("attempt"),
        result.getInt("max_attempts"),
        nullableInstant(result, "next_attempt_at"),
        leaseOwner == null ? null : new WorkerId(leaseOwner),
        leaseToken == null ? null : new LeaseToken(leaseToken),
        nullableInstant(result, "lease_expires_at"),
        errorClass == null ? null : new JobErrorClass(errorClass),
        result.getInt("replay_count"),
        result.getString("trace_id"),
        instant(result, "created_at"),
        instant(result, "updated_at"));
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return result.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static FencedUpdateResult fencedResult(int updated) {
    return updated == 1 ? FencedUpdateResult.UPDATED : FencedUpdateResult.LEASE_LOST;
  }

  private record CompletionIdentity(
      String tenantId,
      UUID jobId,
      UUID sourceEventId,
      String semanticJobKey,
      String handlerVersion,
      Instant completedAt) {}
}
