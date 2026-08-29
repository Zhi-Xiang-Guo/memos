package dev.memos.adapters.postgres;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.CommitProjectionBuild;
import dev.memos.materialization.JobType;
import dev.memos.materialization.ProjectedVersionBuild;
import dev.memos.materialization.ProjectionBuildPlan;
import dev.memos.materialization.ProjectionBuildStore;
import dev.memos.materialization.ProjectionCommitResult;
import dev.memos.materialization.ProjectionSourceItem;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL projection snapshot store with lease fencing and lineage watermarks. */
public final class JdbcProjectionBuildStore implements ProjectionBuildStore {
  private static final int EMBEDDING_DIMENSIONS = 64;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcProjectionBuildStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
  }

  @Override
  public Optional<ProjectionBuildPlan> load(ClaimedJob job) {
    Objects.requireNonNull(job, "job must not be null");
    if (job.jobType() != JobType.PROJECTION_BUILD) {
      throw new IllegalArgumentException("projection store requires a projection job");
    }
    List<ProjectionIdentity> identities =
        jdbc.query(
            """
            SELECT latest.memory_id, latest.transition_id, latest.transition_sequence
              FROM memos.outbox_job job
              JOIN memos.memory_state_transition requested
                ON requested.tenant_id = job.tenant_id
               AND requested.transition_id = job.aggregate_id
              JOIN LATERAL (
                  SELECT transition.memory_id, transition.transition_id,
                         transition.transition_sequence
                    FROM memos.memory_state_transition transition
                   WHERE transition.tenant_id = requested.tenant_id
                     AND transition.memory_id = requested.memory_id
                   ORDER BY transition.transition_sequence DESC
                   LIMIT 1
              ) latest ON true
              JOIN memos.memory_lineage lineage
                ON lineage.tenant_id = requested.tenant_id
               AND lineage.memory_id = requested.memory_id
             WHERE job.tenant_id = ? AND job.job_id = ?
               AND job.source_event_id = ?
               AND job.job_type = 'PROJECTION_BUILD'
               AND job.aggregate_type = 'MEMORY_TRANSITION'
               AND job.state = 'CLAIMED'
               AND job.lease_owner = ? AND job.lease_token = ?
               AND job.lease_expires_at > clock_timestamp()
               AND lineage.user_id = ? AND lineage.agent_id = ?
               AND lineage.lifecycle_state = 'ACTIVE'
            """,
            (result, row) ->
                new ProjectionIdentity(
                    result.getObject("memory_id", UUID.class),
                    result.getObject("transition_id", UUID.class),
                    result.getLong("transition_sequence")),
            job.scope().tenantId(),
            job.jobId().value(),
            job.sourceEventId(),
            job.leaseOwner().value(),
            job.leaseToken().value(),
            job.scope().userId(),
            job.scope().agentId());
    if (identities.size() != 1) {
      return Optional.empty();
    }
    ProjectionIdentity identity = identities.getFirst();
    List<ProjectionSourceItem> items =
        jdbc.query(
            """
            SELECT version.memory_id, version.version_id, lineage.memory_type,
                   lineage.subject_kind, lineage.subject_label, lineage.predicate,
                   current.status, version.normalized_content,
                   version.valid_time_start, version.valid_time_end,
                   version.transaction_time,
                   array_agg(DISTINCT source.source_event_id ORDER BY source.source_event_id)
                     AS source_event_ids
              FROM memos.memory_current_state current
              JOIN memos.memory_version version
                ON version.tenant_id = current.tenant_id
               AND version.memory_id = current.memory_id
               AND version.version_id = current.version_id
              JOIN memos.memory_lineage lineage
                ON lineage.tenant_id = version.tenant_id
               AND lineage.memory_id = version.memory_id
              JOIN memos.memory_source source
                ON source.tenant_id = version.tenant_id
               AND source.memory_id = version.memory_id
               AND source.version_id = version.version_id
             WHERE version.tenant_id = ? AND version.memory_id = ?
               AND lineage.user_id = ? AND lineage.agent_id = ?
               AND lineage.lifecycle_state = 'ACTIVE'
               AND version.content_state = 'AVAILABLE'
               AND current.status <> 'INVALIDATED'
             GROUP BY version.memory_id, version.version_id, lineage.memory_type,
                      lineage.subject_kind, lineage.subject_label, lineage.predicate,
                      current.status, version.normalized_content,
                      version.valid_time_start, version.valid_time_end,
                      version.transaction_time
             ORDER BY version.version_number
            """,
            JdbcProjectionBuildStore::mapSource,
            job.scope().tenantId(),
            identity.memoryId(),
            job.scope().userId(),
            job.scope().agentId());
    return Optional.of(
        new ProjectionBuildPlan(
            job,
            identity.memoryId(),
            identity.transitionId(),
            identity.transitionSequence(),
            items));
  }

  @Override
  public ProjectionCommitResult commit(CommitProjectionBuild command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(status -> commitInTransaction(command));
  }

  private ProjectionCommitResult commitInTransaction(CommitProjectionBuild command) {
    ProjectionBuildPlan plan = command.plan();
    ClaimedJob job = plan.job();
    Integer completed =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM memos.materialization_result
             WHERE tenant_id = ? AND job_id = ? AND semantic_job_key = ?
               AND outcome IN ('PROJECTION_BUILT', 'PROJECTION_SUPERSEDED')
            """,
            Integer.class,
            job.scope().tenantId(),
            job.jobId().value(),
            job.semanticJobKey().value());
    if (completed != null && completed == 1) {
      return ProjectionCommitResult.ALREADY_COMMITTED;
    }
    List<UUID> fenced =
        jdbc.query(
            """
            SELECT job_id FROM memos.outbox_job
             WHERE tenant_id = ? AND job_id = ? AND source_event_id = ?
               AND job_type = 'PROJECTION_BUILD'
               AND aggregate_type = 'MEMORY_TRANSITION'
               AND state = 'CLAIMED' AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
             FOR UPDATE
            """,
            (result, row) -> result.getObject("job_id", UUID.class),
            job.scope().tenantId(),
            job.jobId().value(),
            job.sourceEventId(),
            job.leaseOwner().value(),
            job.leaseToken().value());
    if (fenced.size() != 1) {
      return ProjectionCommitResult.LEASE_LOST;
    }
    List<Long> latest =
        jdbc.query(
            """
            SELECT transition.transition_sequence
              FROM memos.memory_lineage lineage
              JOIN memos.memory_state_transition transition
                ON transition.tenant_id = lineage.tenant_id
               AND transition.memory_id = lineage.memory_id
             WHERE lineage.tenant_id = ? AND lineage.memory_id = ?
               AND lineage.user_id = ? AND lineage.agent_id = ?
             ORDER BY transition.transition_sequence DESC
             LIMIT 1
             FOR UPDATE OF lineage
            """,
            (result, row) -> result.getLong("transition_sequence"),
            job.scope().tenantId(),
            plan.memoryId(),
            job.scope().userId(),
            job.scope().agentId());
    if (latest.size() != 1) {
      throw new IllegalStateException("projection lineage disappeared");
    }
    if (latest.getFirst() != plan.transitionSequence()) {
      complete(job, "PROJECTION_SUPERSEDED");
      return ProjectionCommitResult.SUPERSEDED;
    }
    validate(command);
    jdbc.update(
        "DELETE FROM memos.memory_search_projection WHERE tenant_id = ? AND memory_id = ?",
        job.scope().tenantId(),
        plan.memoryId());
    for (ProjectedVersionBuild projected : command.projectedVersions()) {
      insertProjection(job, plan, projected, command.committedAt());
    }
    jdbc.update(
        """
        INSERT INTO memos.memory_projection_checkpoint (
            tenant_id, user_id, agent_id, memory_id, transition_id,
            transition_sequence, projection_policy_version,
            embedding_model_version, source_job_id, projected_version_count, projected_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, memory_id) DO UPDATE
           SET user_id = EXCLUDED.user_id,
               agent_id = EXCLUDED.agent_id,
               transition_id = EXCLUDED.transition_id,
               transition_sequence = EXCLUDED.transition_sequence,
               projection_policy_version = EXCLUDED.projection_policy_version,
               embedding_model_version = EXCLUDED.embedding_model_version,
               source_job_id = EXCLUDED.source_job_id,
               projected_version_count = EXCLUDED.projected_version_count,
               projected_at = EXCLUDED.projected_at
         WHERE memos.memory_projection_checkpoint.transition_sequence
               <= EXCLUDED.transition_sequence
        """,
        job.scope().tenantId(),
        job.scope().userId(),
        job.scope().agentId(),
        plan.memoryId(),
        plan.transitionId(),
        plan.transitionSequence(),
        job.policyVersion(),
        job.modelVersion(),
        job.jobId().value(),
        command.projectedVersions().size(),
        Timestamp.from(command.committedAt()));
    complete(job, "PROJECTION_BUILT");
    return ProjectionCommitResult.COMMITTED;
  }

  private void insertProjection(
      ClaimedJob job,
      ProjectionBuildPlan plan,
      ProjectedVersionBuild projected,
      Instant projectedAt) {
    ProjectionSourceItem source = projected.source();
    jdbc.update(
        """
        INSERT INTO memos.memory_search_projection (
            tenant_id, user_id, agent_id, memory_id, version_id, memory_type,
            subject_kind, subject_label, predicate, truth_status, normalized_content,
            valid_time_start, valid_time_end, recorded_at, source_event_ids,
            embedding_model_version, embedding_dimensions, embedding,
            projection_policy_version, transition_id, transition_sequence, projected_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?::vector,
                  ?, ?, ?, ?)
        """,
        job.scope().tenantId(),
        job.scope().userId(),
        job.scope().agentId(),
        source.memoryId(),
        source.versionId(),
        source.memoryType().name(),
        source.subjectKind().name(),
        source.subjectLabel(),
        source.predicate(),
        source.status().name(),
        source.normalizedContent(),
        timestamp(source.validFrom()),
        timestamp(source.validTo()),
        Timestamp.from(source.recordedAt()),
        uuidArray(source.sourceEventIds()),
        projected.embedding().modelVersion(),
        projected.embedding().dimensions(),
        vector(projected.embedding().vector()),
        job.policyVersion(),
        plan.transitionId(),
        plan.transitionSequence(),
        Timestamp.from(projectedAt));
  }

  private void complete(ClaimedJob job, String outcome) {
    int updated =
        jdbc.update(
            """
            UPDATE memos.outbox_job
               SET state = 'SUCCEEDED', lease_owner = NULL, lease_token = NULL,
                   lease_expires_at = NULL, next_attempt_at = NULL, error_class = NULL,
                   completed_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = ? AND job_id = ? AND state = 'CLAIMED'
               AND lease_owner = ? AND lease_token = ?
               AND lease_expires_at > clock_timestamp()
            """,
            job.scope().tenantId(),
            job.jobId().value(),
            job.leaseOwner().value(),
            job.leaseToken().value());
    if (updated != 1) {
      throw new IllegalStateException("projection lease was lost during the commit");
    }
    jdbc.update(
        """
        INSERT INTO memos.materialization_result (
            tenant_id, semantic_job_key, job_id, source_event_id, outcome,
            handler_version, completed_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp())
        """,
        job.scope().tenantId(),
        job.semanticJobKey().value(),
        job.jobId().value(),
        job.sourceEventId(),
        outcome,
        job.modelVersion());
  }

  private static void validate(CommitProjectionBuild command) {
    Set<UUID> expected = new HashSet<>();
    command.plan().items().forEach(item -> expected.add(item.versionId()));
    Set<UUID> actual = new HashSet<>();
    for (ProjectedVersionBuild value : command.projectedVersions()) {
      actual.add(value.source().versionId());
      if (value.embedding().dimensions() != EMBEDDING_DIMENSIONS) {
        throw new IllegalArgumentException("projection embedding dimension mismatch");
      }
      if (!value.embedding().modelVersion().equals(command.plan().job().modelVersion())) {
        throw new IllegalArgumentException("projection embedding model mismatch");
      }
    }
    if (!expected.equals(actual) || actual.size() != command.projectedVersions().size()) {
      throw new IllegalArgumentException("projected version identity mismatch");
    }
  }

  private static ProjectionSourceItem mapSource(ResultSet result, int ignored) throws SQLException {
    return new ProjectionSourceItem(
        result.getObject("memory_id", UUID.class),
        result.getObject("version_id", UUID.class),
        MemoryType.valueOf(result.getString("memory_type")),
        SubjectKind.valueOf(result.getString("subject_kind")),
        result.getString("subject_label"),
        result.getString("predicate"),
        AssertionStatus.valueOf(result.getString("status")),
        result.getString("normalized_content"),
        nullableInstant(result, "valid_time_start"),
        nullableInstant(result, "valid_time_end"),
        result.getTimestamp("transaction_time").toInstant(),
        uuidList(result.getArray("source_event_ids")));
  }

  private static List<UUID> uuidList(Array array) throws SQLException {
    Object value = array.getArray();
    if (value instanceof UUID[] identifiers) {
      return List.of(identifiers);
    }
    Object[] values = (Object[]) value;
    return java.util.Arrays.stream(values).map(item -> (UUID) item).toList();
  }

  private static String uuidArray(List<UUID> values) {
    return values.stream()
        .map(UUID::toString)
        .sorted()
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  private static String vector(List<Float> values) {
    return values.stream()
        .map(String::valueOf)
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private record ProjectionIdentity(UUID memoryId, UUID transitionId, long transitionSequence) {}
}
