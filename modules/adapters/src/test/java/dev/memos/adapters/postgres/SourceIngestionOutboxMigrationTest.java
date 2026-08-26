package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SourceIngestionOutboxMigrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector:0.8.6-pg18@sha256:2ba9ca5f2e7daa0f0e7723cba1ee9167bab54efd3640516a44ac1a928dd67e7a")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("memos")
          .withUsername("memos")
          .withPassword("memos-test");

  @BeforeAll
  static void migrate() {
    var flyway =
        Flyway.configure()
            .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
            .defaultSchema("public")
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .load();
    assertThat(flyway.migrate().success).isTrue();
    flyway.validate();
  }

  @Test
  void createsRequiredTablesIndexesAndPayloadFreeLedger() throws Exception {
    try (var connection = connection();
        var tables =
            connection.prepareStatement(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'memos'
                  AND table_name IN ('source_event', 'outbox_job', 'materialization_result')
                ORDER BY table_name
                """)) {
      try (var result = tables.executeQuery()) {
        var names = new ArrayList<String>();
        while (result.next()) {
          names.add(result.getString(1));
        }
        assertThat(names).containsExactly("materialization_result", "outbox_job", "source_event");
      }

      try (var columns =
              connection.prepareStatement(
                  """
                  SELECT column_name, data_type, is_nullable
                  FROM information_schema.columns
                  WHERE table_schema = 'memos'
                    AND table_name = 'materialization_result'
                  ORDER BY ordinal_position
                  """);
          var result = columns.executeQuery()) {
        var types = new HashMap<String, String>();
        while (result.next()) {
          types.put(result.getString("column_name"), result.getString("data_type"));
        }
        assertThat(types)
            .containsOnlyKeys(
                "tenant_id",
                "semantic_job_key",
                "job_id",
                "source_event_id",
                "outcome",
                "handler_version",
                "completed_at",
                "created_at");
        assertThat(types).containsEntry("job_id", "uuid").containsEntry("source_event_id", "uuid");
        assertThat(types.values()).doesNotContain("json", "jsonb", "bytea");
        assertThat(types.keySet())
            .noneMatch(name -> name.contains("payload") || name.contains("content"));
      }

      try (var indexes =
              connection.prepareStatement(
                  """
                  SELECT indexname, indexdef
                  FROM pg_indexes
                  WHERE schemaname = 'memos'
                    AND indexname IN ('outbox_job_claim_idx', 'outbox_job_lease_expiry_idx')
                  """);
          var result = indexes.executeQuery()) {
        var definitions = new HashMap<String, String>();
        while (result.next()) {
          definitions.put(result.getString("indexname"), result.getString("indexdef"));
        }
        assertThat(definitions)
            .containsOnlyKeys("outbox_job_claim_idx", "outbox_job_lease_expiry_idx");
        assertThat(definitions.get("outbox_job_claim_idx"))
            .contains("next_attempt_at", "PENDING", "RETRY_WAIT");
        assertThat(definitions.get("outbox_job_lease_expiry_idx"))
            .contains("lease_expires_at", "CLAIMED");
      }
    }
  }

  @Test
  void enforcesTenantScopedSourceAndRequestUniqueness() throws Exception {
    var suffix = UUID.randomUUID().toString();
    var sourceEventId = UUID.randomUUID();
    var sourceId = "source-" + suffix;
    var idempotencyKey = "idempotency-" + suffix;
    insertSource(sourceEventId, "tenant-a-" + suffix, sourceId, idempotencyKey);

    insertSource(UUID.randomUUID(), "tenant-b-" + suffix, sourceId, idempotencyKey);

    assertSqlState(
        "23505",
        () ->
            insertSource(
                UUID.randomUUID(), "tenant-a-" + suffix, sourceId, "other-idempotency-" + suffix));
    assertSqlState(
        "23505",
        () ->
            insertSource(
                UUID.randomUUID(), "tenant-a-" + suffix, "other-source-" + suffix, idempotencyKey));
    assertSqlState(
        "23503",
        () ->
            insertJob(
                UUID.randomUUID(),
                "tenant-c-" + suffix,
                sourceEventId,
                "semantic-" + suffix,
                "PENDING",
                0,
                0,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                null,
                null));
  }

  @Test
  void enforcesStateLeaseScheduleAttemptReplayAndCompletionInvariants() throws Exception {
    var suffix = UUID.randomUUID().toString();
    var tenantId = "tenant-" + suffix;
    var sourceEventId = UUID.randomUUID();
    insertSource(sourceEventId, tenantId, "source-" + suffix, "idempotency-" + suffix);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    insertJob(
        UUID.randomUUID(),
        tenantId,
        sourceEventId,
        "pending-" + suffix,
        "PENDING",
        0,
        0,
        now,
        null,
        null,
        null,
        null);
    insertJob(
        UUID.randomUUID(),
        tenantId,
        sourceEventId,
        "claimed-" + suffix,
        "CLAIMED",
        1,
        0,
        null,
        "worker-a",
        UUID.randomUUID(),
        now.plusMinutes(1),
        null);

    assertInvalidJob(
        tenantId, sourceEventId, suffix, "UNKNOWN", 0, 0, null, null, null, null, null);
    assertInvalidJob(
        tenantId,
        sourceEventId,
        suffix,
        "CLAIMED",
        1,
        0,
        null,
        "worker-a",
        null,
        now.plusMinutes(1),
        null);
    assertInvalidJob(
        tenantId,
        sourceEventId,
        suffix,
        "PENDING",
        0,
        0,
        now,
        "worker-a",
        UUID.randomUUID(),
        now.plusMinutes(1),
        null);
    assertInvalidJob(
        tenantId, sourceEventId, suffix, "RETRY_WAIT", 4, 0, now, null, null, null, null);
    assertInvalidJob(
        tenantId, sourceEventId, suffix, "RETRY_WAIT", 1, -1, now, null, null, null, null);
    assertInvalidJob(
        tenantId, sourceEventId, suffix, "RETRY_WAIT", 1, 0, null, null, null, null, null);
    assertInvalidJob(
        tenantId, sourceEventId, suffix, "SUCCEEDED", 1, 0, null, null, null, null, null);
  }

  @Test
  void recordsOneTenantScopedPayloadFreeMaterializationResult() throws Exception {
    var suffix = UUID.randomUUID().toString();
    var tenantId = "tenant-" + suffix;
    var sourceEventId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var semanticKey = "semantic-" + suffix;
    var completedAt = OffsetDateTime.now(ZoneOffset.UTC);
    insertSource(sourceEventId, tenantId, "source-" + suffix, "idempotency-" + suffix);
    insertJob(
        jobId,
        tenantId,
        sourceEventId,
        semanticKey,
        "SUCCEEDED",
        1,
        0,
        null,
        null,
        null,
        null,
        completedAt);

    insertResult(tenantId, semanticKey, jobId, sourceEventId, completedAt);
    assertSqlState(
        "23505", () -> insertResult(tenantId, semanticKey, jobId, sourceEventId, completedAt));
    assertSqlState(
        "23503",
        () ->
            insertResult("other-tenant-" + suffix, semanticKey, jobId, sourceEventId, completedAt));
  }

  private static void assertInvalidJob(
      String tenantId,
      UUID sourceEventId,
      String suffix,
      String state,
      int attempt,
      int replayCount,
      OffsetDateTime nextAttemptAt,
      String leaseOwner,
      UUID leaseToken,
      OffsetDateTime leaseExpiresAt,
      OffsetDateTime completedAt) {
    assertSqlState(
        "23514",
        () ->
            insertJob(
                UUID.randomUUID(),
                tenantId,
                sourceEventId,
                "invalid-" + state + "-" + UUID.randomUUID() + "-" + suffix,
                state,
                attempt,
                replayCount,
                nextAttemptAt,
                leaseOwner,
                leaseToken,
                leaseExpiresAt,
                completedAt));
  }

  private static void insertSource(
      UUID sourceEventId, String tenantId, String sourceId, String idempotencyKey)
      throws SQLException {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    execute(
        """
        INSERT INTO memos.source_event (
            source_event_id, tenant_id, user_id, agent_id, source_id, session_id,
            idempotency_key, actor_type, source_type, trust_level, occurred_at,
            received_at, payload, content_fingerprint, request_fingerprint,
            deletion_state, trace_id, created_at
        ) VALUES (?, ?, 'user-1', 'agent-1', ?, 'session-1', ?, 'USER',
                  'CONVERSATION_MESSAGE', 'DIRECT_USER', ?, ?, '{}'::jsonb,
                  decode('cafe', 'hex'), decode('beef', 'hex'), 'ACTIVE', 'trace-test', ?)
        """,
        sourceEventId,
        tenantId,
        sourceId,
        idempotencyKey,
        now.minusSeconds(1),
        now,
        now);
  }

  private static void insertJob(
      UUID jobId,
      String tenantId,
      UUID sourceEventId,
      String semanticKey,
      String state,
      int attempt,
      int replayCount,
      OffsetDateTime nextAttemptAt,
      String leaseOwner,
      UUID leaseToken,
      OffsetDateTime leaseExpiresAt,
      OffsetDateTime completedAt)
      throws SQLException {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var createdAt = completedAt == null ? now : completedAt;
    execute(
        """
        INSERT INTO memos.outbox_job (
            job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
            semantic_job_key,
            policy_version, model_version, state, attempt, max_attempts, replay_count,
            next_attempt_at, payload_reference, lease_owner, lease_token, lease_expires_at, error_class,
            completed_at, trace_id, created_at, updated_at
        ) VALUES (?, ?, ?, 'MATERIALIZE_SOURCE', 'SOURCE_EVENT', ?, ?, 'write-policy-v1',
                  'fake-v1', ?, ?, 3, ?, ?, ?, ?, ?, ?, NULL, ?, 'trace-test', ?, ?)
        """,
        jobId,
        tenantId,
        sourceEventId,
        sourceEventId,
        semanticKey,
        state,
        attempt,
        replayCount,
        nextAttemptAt,
        sourceEventId,
        leaseOwner,
        leaseToken,
        leaseExpiresAt,
        completedAt,
        createdAt,
        now);
  }

  private static void insertResult(
      String tenantId,
      String semanticKey,
      UUID jobId,
      UUID sourceEventId,
      OffsetDateTime completedAt)
      throws SQLException {
    execute(
        """
        INSERT INTO memos.materialization_result (
            tenant_id, semantic_job_key, job_id, source_event_id, outcome,
            handler_version, completed_at, created_at
        ) VALUES (?, ?, ?, ?, 'MATERIALIZED', 'fake-handler-v1', ?, ?)
        """,
        tenantId,
        semanticKey,
        jobId,
        sourceEventId,
        completedAt,
        completedAt);
  }

  private static java.sql.Connection connection() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  private static void execute(String sql, Object... arguments) throws SQLException {
    try (var connection = connection();
        var statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      statement.executeUpdate();
    }
  }

  private static void assertSqlState(String expected, SqlOperation operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(SQLException.class)
        .extracting(throwable -> ((SQLException) throwable).getSQLState())
        .isEqualTo(expected);
  }

  @FunctionalInterface
  private interface SqlOperation {
    void run() throws SQLException;
  }
}
