package dev.memos.adapters.postgres;

import dev.memos.governance.MemoryScope;
import dev.memos.materialization.StorageObservation;
import dev.memos.materialization.StorageObservationStore;
import dev.memos.materialization.StorageRelationObservation;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcStorageObservationStore implements StorageObservationStore {
  private static final String SCOPE_SQL =
      """
      WITH scope AS (
          SELECT ?::varchar AS tenant_id, ?::varchar AS user_id, ?::varchar AS agent_id
      ), scoped_source AS (
          SELECT source.tenant_id, source.source_event_id
            FROM memos.source_event source, scope
           WHERE source.tenant_id = scope.tenant_id
             AND source.user_id = scope.user_id
             AND source.agent_id = scope.agent_id
      ), scoped_memory AS (
          SELECT lineage.tenant_id, lineage.memory_id
            FROM memos.memory_lineage lineage, scope
           WHERE lineage.tenant_id = scope.tenant_id
             AND lineage.user_id = scope.user_id
             AND lineage.agent_id = scope.agent_id
      )
      SELECT relation, row_count, row_bytes
        FROM (
          SELECT 'memos.audit_event' AS relation, count(*)::bigint AS row_count,
                 coalesce(sum(pg_column_size(value)), 0)::bigint AS row_bytes
            FROM memos.audit_event value, scope
           WHERE value.tenant_id = scope.tenant_id AND value.user_id = scope.user_id
             AND value.agent_id = scope.agent_id
          UNION ALL
          SELECT 'memos.candidate_policy_decision', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.candidate_policy_decision value
            JOIN memos.memory_candidate candidate
              ON candidate.tenant_id = value.tenant_id AND candidate.candidate_id = value.candidate_id
            JOIN scoped_source source
              ON source.tenant_id = candidate.tenant_id
             AND source.source_event_id = candidate.source_event_id
          UNION ALL
          SELECT 'memos.deletion_request', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.deletion_request value, scope
           WHERE value.tenant_id = scope.tenant_id
             AND value.requester_user_id = scope.user_id
             AND value.requester_agent_id = scope.agent_id
          UNION ALL
          SELECT 'memos.erasure_tombstone', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.erasure_tombstone value
           WHERE (value.object_type = 'SOURCE_EVENT' AND EXISTS (
                    SELECT 1 FROM scoped_source source
                     WHERE source.tenant_id = value.tenant_id
                       AND source.source_event_id = value.object_id))
              OR (value.object_type = 'MEMORY_LINEAGE' AND EXISTS (
                    SELECT 1 FROM scoped_memory memory
                     WHERE memory.tenant_id = value.tenant_id
                       AND memory.memory_id = value.object_id))
          UNION ALL
          SELECT 'memos.extraction_attempt', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.extraction_attempt value
            JOIN memos.outbox_job job
              ON job.tenant_id = value.tenant_id AND job.job_id = value.job_id
            JOIN scoped_source source
              ON source.tenant_id = job.tenant_id AND source.source_event_id = job.source_event_id
          UNION ALL
          SELECT 'memos.extraction_run', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.extraction_run value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.materialization_result', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.materialization_result value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.memory_candidate', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_candidate value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.memory_current_state', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_current_state value
            JOIN scoped_memory memory
              ON memory.tenant_id = value.tenant_id AND memory.memory_id = value.memory_id
          UNION ALL
          SELECT 'memos.memory_lineage', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_lineage value
            JOIN scoped_memory memory
              ON memory.tenant_id = value.tenant_id AND memory.memory_id = value.memory_id
          UNION ALL
          SELECT 'memos.memory_mutation_request', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_mutation_request value, scope
           WHERE value.tenant_id = scope.tenant_id AND value.user_id = scope.user_id
             AND value.agent_id = scope.agent_id
          UNION ALL
          SELECT 'memos.memory_projection_checkpoint', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_projection_checkpoint value, scope
           WHERE value.tenant_id = scope.tenant_id AND value.user_id = scope.user_id
             AND value.agent_id = scope.agent_id
          UNION ALL
          SELECT 'memos.memory_quarantine', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_quarantine value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.memory_search_projection', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_search_projection value, scope
           WHERE value.tenant_id = scope.tenant_id AND value.user_id = scope.user_id
             AND value.agent_id = scope.agent_id
          UNION ALL
          SELECT 'memos.memory_source', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_source value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.memory_state_transition', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_state_transition value
            JOIN scoped_memory memory
              ON memory.tenant_id = value.tenant_id AND memory.memory_id = value.memory_id
          UNION ALL
          SELECT 'memos.memory_status_change', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_status_change value
            JOIN scoped_memory memory
              ON memory.tenant_id = value.tenant_id AND memory.memory_id = value.memory_id
          UNION ALL
          SELECT 'memos.memory_version', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.memory_version value
            JOIN scoped_memory memory
              ON memory.tenant_id = value.tenant_id AND memory.memory_id = value.memory_id
          UNION ALL
          SELECT 'memos.outbox_job', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.outbox_job value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.projection_provider_usage', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.projection_provider_usage value
            JOIN scoped_source source
              ON source.tenant_id = value.tenant_id
             AND source.source_event_id = value.source_event_id
          UNION ALL
          SELECT 'memos.source_event', count(*)::bigint,
                 coalesce(sum(pg_column_size(value)), 0)::bigint
            FROM memos.source_event value, scope
           WHERE value.tenant_id = scope.tenant_id AND value.user_id = scope.user_id
             AND value.agent_id = scope.agent_id
        ) observations
       ORDER BY relation
      """;

  private static final String DATABASE_SQL =
      """
      SELECT coalesce(sum(pg_table_size(relation.oid)), 0)::bigint AS table_bytes,
             coalesce(sum(pg_indexes_size(relation.oid)), 0)::bigint AS index_bytes,
             coalesce(sum(pg_total_relation_size(relation.oid)), 0)::bigint AS total_bytes
        FROM pg_class relation
        JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
       WHERE namespace.nspname = 'memos' AND relation.relkind IN ('r', 'p')
      """;

  private final JdbcTemplate jdbc;

  public JdbcStorageObservationStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public StorageObservation observe(MemoryScope scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    List<StorageRelationObservation> relations =
        jdbc.query(
            SCOPE_SQL,
            (result, ignored) ->
                new StorageRelationObservation(
                    result.getString("relation"),
                    result.getLong("row_count"),
                    result.getLong("row_bytes")),
            scope.tenantId(),
            scope.userId(),
            scope.agentId());
    long rowCount = relations.stream().mapToLong(StorageRelationObservation::rowCount).sum();
    long rowBytes = relations.stream().mapToLong(StorageRelationObservation::rowBytes).sum();
    DatabaseSize database =
        jdbc.queryForObject(
            DATABASE_SQL,
            (result, ignored) ->
                new DatabaseSize(
                    result.getLong("table_bytes"),
                    result.getLong("index_bytes"),
                    result.getLong("total_bytes")));
    if (database == null) {
      throw new IllegalStateException("database storage observation returned no row");
    }
    return new StorageObservation(
        relations,
        rowCount,
        rowBytes,
        database.tableBytes(),
        database.indexBytes(),
        database.totalBytes());
  }

  private record DatabaseSize(long tableBytes, long indexBytes, long totalBytes) {}
}
