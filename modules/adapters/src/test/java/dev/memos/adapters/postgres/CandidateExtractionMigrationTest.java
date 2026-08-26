package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CandidateExtractionMigrationTest {
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
  void createsExtractionTablesWithoutRawProviderOrQuarantineContentColumns() throws Exception {
    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                """
                SELECT table_name, column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'memos'
                   AND table_name IN (
                       'extraction_attempt', 'extraction_run', 'memory_candidate',
                       'candidate_policy_decision', 'memory_quarantine'
                   )
                 ORDER BY table_name, ordinal_position
                """);
        var result = statement.executeQuery()) {
      var tables = new ArrayList<String>();
      var quarantineColumns = new ArrayList<String>();
      var allColumns = new ArrayList<String>();
      while (result.next()) {
        String table = result.getString("table_name");
        String column = result.getString("column_name");
        tables.add(table);
        allColumns.add(column);
        if (table.equals("memory_quarantine")) {
          quarantineColumns.add(column);
        }
      }

      assertThat(tables)
          .contains(
              "extraction_attempt",
              "extraction_run",
              "memory_candidate",
              "candidate_policy_decision",
              "memory_quarantine");
      assertThat(allColumns)
          .noneMatch(
              column ->
                  column.contains("raw")
                      || column.contains("secret")
                      || column.contains("credential")
                      || column.contains("provider_response")
                      || column.contains("prompt_text"));
      assertThat(quarantineColumns)
          .containsExactlyInAnyOrder(
              "quarantine_id",
              "tenant_id",
              "run_id",
              "attempt_id",
              "source_event_id",
              "job_id",
              "candidate_id",
              "ordinal",
              "reason_code",
              "error_path",
              "state",
              "created_at");
      assertThat(quarantineColumns)
          .noneMatch(
              column ->
                  column.contains("content")
                      || column.contains("payload")
                      || column.contains("value")
                      || column.contains("response"));
    }
  }

  @Test
  void enforcesTenantSemanticAndSanitizedContentInvariants() throws Exception {
    var fixture = fixture("tenant-a");
    insertAttempt(fixture, 1);
    insertRun(fixture, 1, 1, 0, 0);
    insertCandidate(fixture, "AVAILABLE");
    insertDecision(fixture);

    assertSqlState(
        "23505",
        () ->
            insertRun(
                new Fixture(
                    fixture.tenantId(),
                    UUID.randomUUID(),
                    fixture.sourceEventId(),
                    fixture.sourceJobId(),
                    fixture.leaseToken(),
                    fixture.attemptId(),
                    fixture.candidateId(),
                    fixture.semanticRunKey()),
                1,
                1,
                0,
                0));

    assertSqlState(
        "23503",
        () ->
            execute(
                """
                INSERT INTO memos.memory_candidate (
                    candidate_id, tenant_id, run_id, source_event_id, ordinal, schema_version,
                    proposed_decision, subject_kind, subject_label, predicate, value_json, normalized_content,
                    memory_type, importance, confidence, source_type, source_trust,
                    sensitivity, content_fingerprint, content_state, created_at
                ) VALUES (?, 'tenant-b', ?, ?, 1, 'memory-candidate.v1', 'REMEMBER', 'USER', 'user',
                          'preference', '{"value":"tea"}'::jsonb, 'User prefers tea.',
                          'SEMANTIC', 0.7, 0.8, 'CONVERSATION_MESSAGE', 'DIRECT_USER',
                          ARRAY[]::text[], decode('cafe', 'hex'), 'AVAILABLE', clock_timestamp())
                """,
                UUID.randomUUID(),
                fixture.runId(),
                fixture.sourceEventId()));

    assertSqlState(
        "23514",
        () ->
            execute(
                """
                UPDATE memos.memory_candidate
                   SET content_state = 'ERASED'
                 WHERE candidate_id = ?
                """,
                fixture.candidateId()));
  }

  @Test
  void policyDecisionIsAppendOnlyAndQuarantineIsContentFree() throws Exception {
    var fixture = fixture("tenant-policy");
    insertAttempt(fixture, 1);
    insertRun(fixture, 1, 0, 0, 1);
    insertCandidate(fixture, "AVAILABLE");
    insertDecision(fixture);
    execute(
        """
        INSERT INTO memos.memory_quarantine (
            quarantine_id, tenant_id, run_id, attempt_id, source_event_id, job_id,
            candidate_id, ordinal,
            reason_code, state, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'SENSITIVE_REVIEW_REQUIRED', 'OPEN', clock_timestamp())
        """,
        UUID.randomUUID(),
        fixture.tenantId(),
        fixture.runId(),
        fixture.attemptId(),
        fixture.sourceEventId(),
        fixture.sourceJobId(),
        fixture.candidateId());

    assertSqlState(
        "55000",
        () ->
            execute(
                "UPDATE memos.candidate_policy_decision SET decision = 'IGNORE' WHERE candidate_id = ?",
                fixture.candidateId()));
    assertSqlState(
        "55000",
        () ->
            execute(
                "DELETE FROM memos.candidate_policy_decision WHERE candidate_id = ?",
                fixture.candidateId()));
  }

  @Test
  void attemptIdentityIncludesLeaseEpochAndStartedCallsCanBeAbandoned() throws Exception {
    var fixture = fixture("tenant-replay");
    insertAttempt(fixture, 1);

    var replacementLease = UUID.randomUUID();
    execute(
        "UPDATE memos.outbox_job SET lease_token = ?, updated_at = clock_timestamp() WHERE job_id = ?",
        replacementLease,
        fixture.sourceJobId());
    var replayAttempt =
        new Fixture(
            fixture.tenantId(),
            UUID.randomUUID(),
            fixture.sourceEventId(),
            fixture.sourceJobId(),
            replacementLease,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "run/" + UUID.randomUUID());
    insertAttempt(replayAttempt, 1);

    assertSqlState(
        "23505",
        () ->
            insertAttempt(
                new Fixture(
                    replayAttempt.tenantId(),
                    UUID.randomUUID(),
                    replayAttempt.sourceEventId(),
                    replayAttempt.sourceJobId(),
                    replayAttempt.leaseToken(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "run/" + UUID.randomUUID()),
                1));

    var startedAttemptId = UUID.randomUUID();
    execute(
        """
        INSERT INTO memos.extraction_attempt (
            attempt_id, tenant_id, job_id, job_attempt, lease_token, provider,
            model_version, prompt_version, schema_version, policy_version, state,
            started_at, finished_at, input_tokens, output_tokens, model_calls,
            duration_ms, finish_reason, error_class
        ) VALUES (?, ?, ?, 2, ?, 'deterministic-fake', 'fake-model.v1', 'prompt.v1',
                  'memory-candidate.v1', 'write-policy.v1', 'STARTED', clock_timestamp(),
                  NULL, NULL, NULL, 0, NULL, NULL, NULL)
        """,
        startedAttemptId,
        fixture.tenantId(),
        fixture.sourceJobId(),
        replacementLease);
    execute(
        """
        UPDATE memos.extraction_attempt
           SET state = 'ABANDONED', finished_at = clock_timestamp(),
               error_class = 'LEASE_REPLACED'
         WHERE attempt_id = ?
        """,
        startedAttemptId);
  }

  private static Fixture fixture(String tenantId) throws SQLException {
    var sourceEventId = UUID.randomUUID();
    var sourceJobId = UUID.randomUUID();
    var leaseToken = UUID.randomUUID();
    var fixture =
        new Fixture(
            tenantId,
            UUID.randomUUID(),
            sourceEventId,
            sourceJobId,
            leaseToken,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "run/" + UUID.randomUUID());
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    execute(
        """
        INSERT INTO memos.source_event (
            source_event_id, tenant_id, user_id, agent_id, source_id, session_id,
            idempotency_key, actor_type, source_type, trust_level, occurred_at,
            received_at, payload, content_fingerprint, request_fingerprint,
            deletion_state, trace_id, created_at
        ) VALUES (?, ?, 'user-1', 'agent-1', ?, 'session-1', ?, 'USER',
                  'CONVERSATION_MESSAGE', 'DIRECT_USER', ?, ?, '{"content":"tea"}'::jsonb,
                  decode('cafe', 'hex'), decode('beef', 'hex'), 'ACTIVE', 'trace-test', ?)
        """,
        sourceEventId,
        tenantId,
        "source-" + sourceEventId,
        "idempotency-" + sourceEventId,
        now.minusSeconds(1),
        now,
        now);
    execute(
        """
        INSERT INTO memos.outbox_job (
            job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
            semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
            replay_count, next_attempt_at, payload_reference, lease_owner, lease_token,
            lease_expires_at, error_class, completed_at, trace_id, created_at, updated_at
        ) VALUES (?, ?, ?, 'MATERIALIZE_SOURCE', 'SOURCE_EVENT', ?, ?, 'write-policy.v1',
                  'fake-model.v1', 'CLAIMED', 1, 3, 0, NULL, ?, 'worker-a', ?, ?, NULL,
                  NULL, 'trace-test', ?, ?)
        """,
        sourceJobId,
        tenantId,
        sourceEventId,
        sourceEventId,
        "materialize/" + sourceEventId,
        sourceEventId,
        leaseToken,
        now.plusMinutes(5),
        now,
        now);
    return fixture;
  }

  private static void insertAttempt(Fixture fixture, int jobAttempt) throws SQLException {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    execute(
        """
        INSERT INTO memos.extraction_attempt (
            attempt_id, tenant_id, job_id, job_attempt, lease_token, provider,
            model_version, prompt_version, schema_version, policy_version, state,
            started_at, finished_at, input_tokens, output_tokens, model_calls,
            duration_ms, finish_reason, error_class
        ) VALUES (?, ?, ?, ?, ?, 'deterministic-fake', 'fake-model.v1', 'prompt.v1',
                  'memory-candidate.v1', 'write-policy.v1', 'SUCCEEDED', ?, ?,
                  10, 5, 1, 20, 'STOP', NULL)
        """,
        fixture.attemptId(),
        fixture.tenantId(),
        fixture.sourceJobId(),
        jobAttempt,
        fixture.leaseToken(),
        now.minusNanos(20_000_000),
        now);
  }

  private static void insertRun(
      Fixture fixture, int candidateCount, int rememberCount, int ignoreCount, int reviewCount)
      throws SQLException {
    execute(
        """
        INSERT INTO memos.extraction_run (
            run_id, tenant_id, source_event_id, extraction_job_id, semantic_run_key,
            attempt_id, provider, model_version, prompt_version, schema_version,
            policy_version, candidate_count, remember_count, ignore_count, review_count,
            created_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'deterministic-fake', 'fake-model.v1', 'prompt.v1',
                  'memory-candidate.v1', 'write-policy.v1', ?, ?, ?, ?, clock_timestamp())
        """,
        fixture.runId(),
        fixture.tenantId(),
        fixture.sourceEventId(),
        fixture.sourceJobId(),
        fixture.semanticRunKey(),
        fixture.attemptId(),
        candidateCount,
        rememberCount,
        ignoreCount,
        reviewCount);
  }

  private static void insertCandidate(Fixture fixture, String contentState) throws SQLException {
    execute(
        """
        INSERT INTO memos.memory_candidate (
            candidate_id, tenant_id, run_id, source_event_id, ordinal, schema_version,
            proposed_decision, subject_kind, subject_label, predicate, value_json, normalized_content,
            memory_type, event_time_json, valid_interval_json, importance, confidence,
            source_type, source_trust, sensitivity, relation_hints_json,
            content_fingerprint, content_state, created_at
        ) VALUES (?, ?, ?, ?, 0, 'memory-candidate.v1', 'REMEMBER', 'USER', 'user', 'preference',
                  '{"value":"coffee"}'::jsonb, 'User prefers coffee.', 'SEMANTIC',
                  NULL, NULL, 0.7, 0.8, 'CONVERSATION_MESSAGE', 'DIRECT_USER',
                  ARRAY[]::text[], '[]'::jsonb, decode('cafe', 'hex'), ?, clock_timestamp())
        """,
        fixture.candidateId(),
        fixture.tenantId(),
        fixture.runId(),
        fixture.sourceEventId(),
        contentState);
  }

  private static void insertDecision(Fixture fixture) throws SQLException {
    execute(
        """
        INSERT INTO memos.candidate_policy_decision (
            decision_id, tenant_id, run_id, candidate_id, ordinal, decision,
            sensitivity_action, effective_scope, reason_codes, policy_version, decided_at
        ) VALUES (?, ?, ?, ?, 0, 'REMEMBER', 'NONE', 'USER_PRIVATE',
                  ARRAY['POLICY_ACCEPTED'], 'write-policy.v1', clock_timestamp())
        """,
        UUID.randomUUID(),
        fixture.tenantId(),
        fixture.runId(),
        fixture.candidateId());
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

  private record Fixture(
      String tenantId,
      UUID runId,
      UUID sourceEventId,
      UUID sourceJobId,
      UUID leaseToken,
      UUID attemptId,
      UUID candidateId,
      String semanticRunKey) {}

  @FunctionalInterface
  private interface SqlOperation {
    void run() throws SQLException;
  }
}
