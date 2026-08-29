package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.adapters.json.JacksonPayloadCanonicalizer;
import dev.memos.adapters.system.UuidIngestionIdentifierGenerator;
import dev.memos.audit.TraceAccessAuditEvent;
import dev.memos.governance.DeletionAuthority;
import dev.memos.governance.DeletionOperation;
import dev.memos.governance.DeletionPolicyBasis;
import dev.memos.governance.DeletionRequestCommand;
import dev.memos.governance.DeletionRequestDisposition;
import dev.memos.governance.DeletionRequestException;
import dev.memos.governance.DeletionRequestFailure;
import dev.memos.governance.DeletionRequeueCommand;
import dev.memos.governance.DeletionState;
import dev.memos.governance.DeletionStoreResult;
import dev.memos.governance.DeletionTargetType;
import dev.memos.governance.GovernedDeletionService;
import dev.memos.governance.MemoryScope;
import dev.memos.ingestion.ActorType;
import dev.memos.ingestion.IngestionConflict;
import dev.memos.ingestion.IngestionConflictException;
import dev.memos.ingestion.IngestionTelemetry;
import dev.memos.ingestion.SourceIngestionCommand;
import dev.memos.ingestion.SourceIngestionService;
import dev.memos.ingestion.SourceType;
import dev.memos.ingestion.TrustLevel;
import dev.memos.materialization.JobId;
import dev.memos.materialization.ReplayResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class JdbcDeletionStoreIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector:0.8.6-pg18@sha256:2ba9ca5f2e7daa0f0e7723cba1ee9167bab54efd3640516a44ac1a928dd67e7a")
          .asCompatibleSubstituteFor("postgres");
  private static final String RETAINED_CONTENT = "The user lives in Hangzhou.";

  @Container
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("memos")
          .withUsername("memos")
          .withPassword("memos-test");

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcDeletionStore deletionStore;
  private GovernedDeletionService deletions;
  private JdbcMaterializationJobStore jobs;

  @BeforeAll
  static void migrate() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
            .defaultSchema("public")
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .load();
    assertThat(flyway.migrate().success).isTrue();
    flyway.validate();
  }

  @BeforeEach
  void setUp() {
    dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    deletionStore =
        new JdbcDeletionStore(jdbc, transactions, UUID::randomUUID, "erasure-policy.v1");
    deletions = new GovernedDeletionService(deletionStore, UUID::randomUUID, 3);
    jobs = new JdbcMaterializationJobStore(jdbc, transactions);
    jdbc.execute("TRUNCATE memos.source_event, memos.deletion_request, memos.audit_event CASCADE");
  }

  @Test
  void memoryDeletionIsTenantScopedIdempotentAndImmediatelyInvisible() throws Exception {
    Fixture fixture = fixture("tenant-delete-scope", "user-1", "agent-1");

    assertThatThrownBy(
            () ->
                requestMemory(
                    new MemoryScope("foreign-tenant", "user-1", "agent-1"),
                    fixture.memoryId(),
                    "delete-key",
                    DeletionPolicyBasis.USER_REQUEST))
        .isInstanceOf(DeletionRequestException.class)
        .extracting(exception -> ((DeletionRequestException) exception).failure())
        .isEqualTo(DeletionRequestFailure.NOT_FOUND);
    assertThat(lineageState(fixture)).isEqualTo("ACTIVE");

    var accepted =
        requestMemory(
            fixture.scope(), fixture.memoryId(), "delete-key", DeletionPolicyBasis.USER_REQUEST);
    var replay =
        requestMemory(
            fixture.scope(), fixture.memoryId(), "delete-key", DeletionPolicyBasis.USER_REQUEST);
    var alreadyRequested =
        requestMemory(
            fixture.scope(), fixture.memoryId(), "second-key", DeletionPolicyBasis.USER_REQUEST);

    assertThat(accepted.disposition()).isEqualTo(DeletionRequestDisposition.ACCEPTED);
    assertThat(replay.disposition()).isEqualTo(DeletionRequestDisposition.IDEMPOTENT_REPLAY);
    assertThat(replay.operation().operationId()).isEqualTo(accepted.operation().operationId());
    assertThat(alreadyRequested.disposition())
        .isEqualTo(DeletionRequestDisposition.ALREADY_REQUESTED);
    assertThat(alreadyRequested.operation().operationId())
        .isEqualTo(accepted.operation().operationId());
    assertThatThrownBy(
            () ->
                requestMemory(
                    new MemoryScope(fixture.tenantId(), fixture.userId(), "other-agent"),
                    fixture.memoryId(),
                    "delete-key",
                    DeletionPolicyBasis.USER_REQUEST))
        .isInstanceOf(DeletionRequestException.class)
        .extracting(exception -> ((DeletionRequestException) exception).failure())
        .isEqualTo(DeletionRequestFailure.IDEMPOTENCY_CONFLICT);

    assertThat(lineageState(fixture)).isEqualTo("DELETE_REQUESTED");
    assertThat(count("memos.memory_search_projection")).isZero();
    assertThat(count("memos.memory_projection_checkpoint")).isZero();
    assertThat(jobState(fixture.projectionJobId())).containsExactly("DEAD", "GOVERNED_ERASURE");
    assertThat(
            deletions.find(fixture.tenantId(), "other-subject", accepted.operation().operationId()))
        .isEmpty();
  }

  @Test
  void memoryErasePurgesContentAndOldProjectionJobCannotBeReplayed() throws Exception {
    Fixture fixture = fixture("tenant-memory-erase", "user-1", "agent-1");
    var request =
        requestMemory(
            fixture.scope(), fixture.memoryId(), "delete-memory", DeletionPolicyBasis.USER_REQUEST);

    var claimed =
        deletionStore.claim("delete-worker", 1, Instant.now(), Duration.ofMinutes(1)).getFirst();
    assertThat(deletionStore.erase(claimed, Instant.now())).isEqualTo(DeletionStoreResult.UPDATED);

    DeletionOperation completed =
        deletions
            .find(fixture.tenantId(), "subject-user-1", request.operation().operationId())
            .orElseThrow();
    assertThat(completed.state()).isEqualTo(DeletionState.COMPLETED);
    assertThat(lineageState(fixture)).isEqualTo("ERASED");
    assertThat(
            jdbc.queryForMap(
                "SELECT subject_label, predicate FROM memos.memory_lineage WHERE memory_id = ?",
                fixture.memoryId()))
        .containsEntry("subject_label", null)
        .containsEntry("predicate", null);
    assertErasedContent(fixture);
    assertThat(count("memos.memory_current_state")).isZero();
    assertThat(count("memos.memory_search_projection")).isZero();
    assertThat(count("memos.memory_projection_checkpoint")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.erasure_tombstone WHERE tenant_id = ? AND object_type = 'MEMORY_LINEAGE' AND object_id = ?",
                Integer.class,
                fixture.tenantId(),
                fixture.memoryId()))
        .isEqualTo(1);
    assertThat(jobs.replay(fixture.scope(), new JobId(fixture.projectionJobId()), Instant.now()))
        .isEqualTo(ReplayResult.NOT_REPLAYABLE);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM memos.erasure_tombstone WHERE tenant_id = ? AND object_id = ?",
                    fixture.tenantId(),
                    fixture.memoryId()))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT payload ->> 'content' FROM memos.source_event WHERE source_event_id = ?",
                String.class,
                fixture.sourceEventId()))
        .isEqualTo("moved to Hangzhou");
  }

  @Test
  void leaseExpiryRollsBackPartialEraseAndNextClaimCompletes() throws Exception {
    Fixture fixture = fixture("tenant-lease-rollback", "user-1", "agent-1");
    requestMemory(
        fixture.scope(), fixture.memoryId(), "lease-delete", DeletionPolicyBasis.USER_REQUEST);
    var stale =
        deletionStore.claim("stale-worker", 1, Instant.now(), Duration.ofMillis(300)).getFirst();
    jdbc.execute(
        """
        CREATE FUNCTION memos.delay_erasure_for_test() RETURNS trigger AS $$
        BEGIN
            IF NEW.lifecycle_state = 'ERASED' THEN
                PERFORM pg_sleep(0.5);
            END IF;
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """);
    jdbc.execute(
        """
        CREATE TRIGGER delay_erasure_for_test
        BEFORE UPDATE ON memos.memory_lineage
        FOR EACH ROW EXECUTE FUNCTION memos.delay_erasure_for_test()
        """);

    try {
      assertThat(deletionStore.erase(stale, Instant.now()))
          .isEqualTo(DeletionStoreResult.LEASE_LOST);
    } finally {
      jdbc.execute("DROP TRIGGER delay_erasure_for_test ON memos.memory_lineage");
      jdbc.execute("DROP FUNCTION memos.delay_erasure_for_test()");
    }

    assertThat(lineageState(fixture)).isEqualTo("DELETE_REQUESTED");
    assertThat(contentState("memos.memory_version", "version_id", fixture.versionId()))
        .isEqualTo("AVAILABLE");
    assertThat(contentState("memos.memory_candidate", "candidate_id", fixture.candidateId()))
        .isEqualTo("AVAILABLE");
    assertThat(count("memos.erasure_tombstone")).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM memos.deletion_request", String.class))
        .isEqualTo("CLAIMED");

    var reclaimed =
        deletionStore.claim("recovery-worker", 1, Instant.now(), Duration.ofMinutes(1)).getFirst();
    assertThat(reclaimed.operation().attempt()).isEqualTo(2);
    assertThat(deletionStore.erase(reclaimed, Instant.now()))
        .isEqualTo(DeletionStoreResult.UPDATED);
    assertErasedContent(fixture);
  }

  @Test
  void userDeletionSerializesWithIngestionAndBlocksReplayAndFutureWrites() throws Exception {
    Fixture fixture = fixture("tenant-user-erase", "user-1", "agent-1");
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var deletion =
          executor.submit(
              () -> {
                start.await();
                return requestUser(fixture.tenantId(), fixture.userId(), "delete-user");
              });
      var ingestion =
          executor.submit(
              () -> {
                start.await();
                try {
                  ingestionService()
                      .ingest(
                          ingestionCommand(
                              fixture.tenantId(), fixture.userId(), "concurrent-source"));
                  return "ACCEPTED";
                } catch (IngestionConflictException exception) {
                  return exception.reason().name();
                }
              });
      start.countDown();
      assertThat(deletion.get().disposition()).isEqualTo(DeletionRequestDisposition.ACCEPTED);
      assertThat(ingestion.get()).isIn("ACCEPTED", IngestionConflict.USER_SCOPE_ERASED.name());
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.source_event WHERE tenant_id = ? AND user_id = ? AND deletion_state = 'ACTIVE'",
                Integer.class,
                fixture.tenantId(),
                fixture.userId()))
        .isZero();
    assertThat(jobs.replay(fixture.scope(), new JobId(fixture.sourceJobId()), Instant.now()))
        .isEqualTo(ReplayResult.NOT_REPLAYABLE);
    assertThatThrownBy(
            () ->
                ingestionService()
                    .ingest(
                        ingestionCommand(
                            fixture.tenantId(), fixture.userId(), "post-delete-source")))
        .isInstanceOf(IngestionConflictException.class)
        .extracting(exception -> ((IngestionConflictException) exception).reason())
        .isEqualTo(IngestionConflict.USER_SCOPE_ERASED);

    var claimed =
        deletionStore
            .claim("user-delete-worker", 1, Instant.now(), Duration.ofMinutes(1))
            .getFirst();
    assertThat(deletionStore.erase(claimed, Instant.now())).isEqualTo(DeletionStoreResult.UPDATED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.source_event WHERE tenant_id = ? AND user_id = ? AND deletion_state <> 'ERASED'",
                Integer.class,
                fixture.tenantId(),
                fixture.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.source_event WHERE tenant_id = ? AND user_id = ? AND (payload <> '{}'::jsonb OR content_fingerprint IS NOT NULL OR request_fingerprint IS NOT NULL)",
                Integer.class,
                fixture.tenantId(),
                fixture.userId()))
        .isZero();
    assertErasedContent(fixture);
  }

  @Test
  void privacyAdministratorRequeuesDeadDeletionWithoutReopeningTheScope() throws Exception {
    Fixture fixture = fixture("tenant-dead-requeue", "user-1", "agent-1");
    DeletionOperation requested =
        requestMemory(
                fixture.scope(),
                fixture.memoryId(),
                "dead-delete",
                DeletionPolicyBasis.USER_REQUEST)
            .operation();
    var claimed =
        deletionStore.claim("failing-worker", 1, Instant.now(), Duration.ofMinutes(1)).getFirst();
    assertThat(deletionStore.markDead(claimed, "FORCED_TEST_FAILURE", Instant.now()))
        .isEqualTo(DeletionStoreResult.UPDATED);

    var alreadyRequested =
        requestMemory(
            fixture.scope(),
            fixture.memoryId(),
            "new-key-after-dead",
            DeletionPolicyBasis.USER_REQUEST);
    assertThat(alreadyRequested.disposition())
        .isEqualTo(DeletionRequestDisposition.ALREADY_REQUESTED);
    assertThat(alreadyRequested.operation().operationId()).isEqualTo(requested.operationId());
    assertThat(alreadyRequested.operation().state()).isEqualTo(DeletionState.DEAD);
    assertThat(lineageState(fixture)).isEqualTo("DELETE_REQUESTED");

    assertThat(
            deletions.requeue(
                requeueCommand("foreign-tenant", requested.operationId(), "trace-foreign")))
        .isEmpty();
    DeletionOperation requeued =
        deletions
            .requeue(requeueCommand(fixture.tenantId(), requested.operationId(), "trace-requeue"))
            .orElseThrow();
    assertThat(requeued.state()).isEqualTo(DeletionState.PENDING);
    assertThat(requeued.attempt()).isZero();
    assertThat(requeued.maxAttempts()).isEqualTo(3);
    assertThat(requeued.errorClass()).isNull();
    assertThat(requeued.completedAt()).isNull();
    assertThat(deletions.findForTenant(fixture.tenantId(), requested.operationId()))
        .contains(requeued);
    assertThat(
            jdbc.queryForMap(
                "SELECT actor_type, actor_id, action, reason_code, trace_id FROM memos.audit_event WHERE action = 'DELETE_REQUEUED'"))
        .containsEntry("actor_type", "OPERATOR")
        .containsEntry("actor_id", "privacy-subject")
        .containsEntry("action", "DELETE_REQUEUED")
        .containsEntry("reason_code", "PRIVACY_ADMIN_REQUEUE")
        .containsEntry("trace_id", "trace-requeue");

    var recoveryClaim =
        deletionStore.claim("recovery-worker", 1, Instant.now(), Duration.ofMinutes(1)).getFirst();
    assertThat(recoveryClaim.operation().operationId()).isEqualTo(requested.operationId());
    assertThat(deletionStore.erase(recoveryClaim, Instant.now()))
        .isEqualTo(DeletionStoreResult.UPDATED);
  }

  @Test
  void traceAccessAuditPersistsOnlyContentSafeOperatorMetadata() {
    UUID auditId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-30T01:02:03Z");
    var audit = new JdbcTraceAccessAudit(jdbc, () -> auditId, "access-control.v1");

    audit.record(
        new TraceAccessAuditEvent(
            "tenant-a",
            "operator-user",
            "operator-agent",
            "operator-subject",
            "trace-a",
            occurredAt));

    assertThat(
            jdbc.queryForMap(
                "SELECT audit_event_id, tenant_id, user_id, agent_id, actor_type, actor_id, action, target_type, outcome, reason_code, policy_version, trace_id FROM memos.audit_event WHERE audit_event_id = ?",
                auditId))
        .containsEntry("audit_event_id", auditId)
        .containsEntry("tenant_id", "tenant-a")
        .containsEntry("user_id", "operator-user")
        .containsEntry("agent_id", "operator-agent")
        .containsEntry("actor_type", "OPERATOR")
        .containsEntry("actor_id", "operator-subject")
        .containsEntry("action", "RETRIEVAL_TRACE_ACCESSED")
        .containsEntry("target_type", "RETRIEVAL_TRACE")
        .containsEntry("outcome", "SUCCEEDED")
        .containsEntry("reason_code", "ROLE_AUTHORIZED")
        .containsEntry("policy_version", "access-control.v1")
        .containsEntry("trace_id", "trace-a");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.audit_event WHERE audit_event_id = ? AND actor_id <> ?",
                Integer.class,
                auditId,
                RETAINED_CONTENT))
        .isEqualTo(1);
  }

  private static DeletionRequeueCommand requeueCommand(
      String tenantId, UUID operationId, String traceId) {
    return new DeletionRequeueCommand(
        new MemoryScope(tenantId, "privacy-admin", "admin-agent"),
        "privacy-subject",
        DeletionAuthority.PRIVACY_ADMIN,
        operationId,
        traceId,
        Instant.now());
  }

  private dev.memos.governance.DeletionRequestResult requestMemory(
      MemoryScope scope, UUID memoryId, String idempotencyKey, DeletionPolicyBasis policyBasis) {
    return deletions.request(
        new DeletionRequestCommand(
            scope,
            "subject-" + scope.userId(),
            DeletionAuthority.SELF_SERVICE,
            DeletionTargetType.MEMORY,
            memoryId,
            null,
            idempotencyKey,
            policyBasis,
            "trace-delete",
            Instant.now()));
  }

  private dev.memos.governance.DeletionRequestResult requestUser(
      String tenantId, String userId, String idempotencyKey) {
    return deletions.request(
        new DeletionRequestCommand(
            new MemoryScope(tenantId, "privacy-admin", "admin-agent"),
            "privacy-subject",
            DeletionAuthority.PRIVACY_ADMIN,
            DeletionTargetType.USER,
            null,
            userId,
            idempotencyKey,
            DeletionPolicyBasis.LEGAL_ERASURE,
            "trace-privacy",
            Instant.now()));
  }

  private SourceIngestionService ingestionService() {
    var mapper =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    return new SourceIngestionService(
        Clock.systemUTC(),
        new UuidIngestionIdentifierGenerator(),
        new JacksonPayloadCanonicalizer(mapper),
        new JdbcSourceIngestionStore(jdbc, transactions),
        IngestionTelemetry.NOOP,
        "ingestion-v1",
        "deterministic-fake-v1",
        5);
  }

  private static SourceIngestionCommand ingestionCommand(
      String tenantId, String userId, String identity) {
    return new SourceIngestionCommand(
        new MemoryScope(tenantId, userId, "agent-2"),
        identity,
        "session-concurrent",
        identity,
        ActorType.USER,
        SourceType.CONVERSATION_MESSAGE,
        TrustLevel.DIRECT_USER,
        Instant.now(),
        "{\"content\":\"concurrent content\"}",
        "trace-concurrent");
  }

  private void assertErasedContent(Fixture fixture) {
    assertThat(contentState("memos.memory_version", "version_id", fixture.versionId()))
        .isEqualTo("ERASED");
    assertThat(contentState("memos.memory_candidate", "candidate_id", fixture.candidateId()))
        .isEqualTo("ERASED");
    assertThat(
            jdbc.queryForObject(
                "SELECT normalized_content FROM memos.memory_version WHERE version_id = ?",
                String.class,
                fixture.versionId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT normalized_content FROM memos.memory_candidate WHERE candidate_id = ?",
                String.class,
                fixture.candidateId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT encode(content_fingerprint, 'hex') FROM memos.memory_version WHERE version_id = ?",
                String.class,
                fixture.versionId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT reason || ':' || actor_id FROM memos.memory_state_transition WHERE transition_id = ?",
                String.class,
                fixture.transitionId()))
        .isEqualTo("ERASED:ERASED");
  }

  private String lineageState(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT lifecycle_state FROM memos.memory_lineage WHERE tenant_id = ? AND memory_id = ?",
        String.class,
        fixture.tenantId(),
        fixture.memoryId());
  }

  private String contentState(String table, String idColumn, UUID id) {
    return jdbc.queryForObject(
        "SELECT content_state FROM " + table + " WHERE " + idColumn + " = ?", String.class, id);
  }

  private java.util.List<String> jobState(UUID jobId) {
    return jdbc.queryForObject(
        "SELECT ARRAY[state, error_class] FROM memos.outbox_job WHERE job_id = ?",
        (result, row) ->
            java.util.List.of(result.getString(1).replace("{", "").replace("}", "").split(",")),
        jobId);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private Fixture fixture(String tenantPrefix, String userId, String agentId) throws Exception {
    String tenantId = tenantPrefix + "-" + UUID.randomUUID();
    UUID sourceEventId = UUID.randomUUID();
    UUID sourceJobId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    UUID memoryId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID projectionJobId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      execute(
          connection,
          """
          INSERT INTO memos.source_event (
              source_event_id, tenant_id, user_id, agent_id, source_id, session_id,
              idempotency_key, actor_type, source_type, trust_level, occurred_at,
              received_at, payload, content_fingerprint, request_fingerprint,
              deletion_state, trace_id, created_at
          ) VALUES (?, ?, ?, ?, ?, 'session-1', ?, 'USER', 'DIRECT_MEMORY_COMMAND',
                    'DIRECT_USER', ?, ?, '{"content":"moved to Hangzhou"}'::jsonb,
                    decode('aabb', 'hex'), decode('beef', 'hex'), 'ACTIVE', 'trace-fixture', ?)
          """,
          sourceEventId,
          tenantId,
          userId,
          agentId,
          "source-" + sourceEventId,
          "source-key-" + sourceEventId,
          now.minusSeconds(1),
          now,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.outbox_job (
              job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
              semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
              replay_count, next_attempt_at, payload_reference, lease_owner, lease_token,
              lease_expires_at, trace_id, created_at, updated_at
          ) VALUES (?, ?, ?, 'CANDIDATE_MATERIALIZATION', 'EXTRACTION_RUN', ?, ?,
                    'write-policy.v1', 'model.v1', 'CLAIMED', 1, 3, 0, NULL, ?,
                    'fixture-worker', ?, ?, 'trace-fixture', ?, ?)
          """,
          sourceJobId,
          tenantId,
          sourceEventId,
          runId,
          "candidate/" + runId,
          sourceEventId,
          leaseToken,
          now.plusMinutes(5),
          now,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.extraction_attempt (
              attempt_id, tenant_id, job_id, job_attempt, lease_token, provider,
              model_version, prompt_version, schema_version, policy_version, state,
              started_at, finished_at, input_tokens, output_tokens, model_calls,
              duration_ms, finish_reason
          ) VALUES (?, ?, ?, 1, ?, 'fake', 'model.v1', 'prompt.v1',
                    'memory-candidate.v1', 'write-policy.v1', 'SUCCEEDED', ?, ?,
                    1, 1, 1, 1, 'VALID_SCHEMA')
          """,
          attemptId,
          tenantId,
          sourceJobId,
          leaseToken,
          now.minusSeconds(1),
          now);
      execute(
          connection,
          """
          INSERT INTO memos.extraction_run (
              run_id, tenant_id, source_event_id, extraction_job_id, semantic_run_key,
              attempt_id, provider, model_version, prompt_version, schema_version,
              policy_version, candidate_count, remember_count, ignore_count, review_count,
              created_at
          ) VALUES (?, ?, ?, ?, ?, ?, 'fake', 'model.v1', 'prompt.v1',
                    'memory-candidate.v1', 'write-policy.v1', 1, 1, 0, 0, ?)
          """,
          runId,
          tenantId,
          sourceEventId,
          sourceJobId,
          "extract/" + runId,
          attemptId,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.memory_candidate (
              candidate_id, tenant_id, run_id, source_event_id, ordinal, schema_version,
              proposed_decision, subject_kind, subject_label, predicate, value_json,
              normalized_content, memory_type, importance, confidence, source_type,
              source_trust, sensitivity, relation_hints_json, content_fingerprint,
              content_state, created_at
          ) VALUES (?, ?, ?, ?, 0, 'memory-candidate.v1', 'REMEMBER', 'USER', ?,
                    'residence', '"Hangzhou"'::jsonb, ?, 'SEMANTIC', 0.8, 0.9,
                    'CONVERSATION_MESSAGE', 'DIRECT_USER', ARRAY['LOCATION'], '[]'::jsonb,
                    decode('cafe', 'hex'), 'AVAILABLE', ?)
          """,
          candidateId,
          tenantId,
          runId,
          sourceEventId,
          userId,
          RETAINED_CONTENT,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.candidate_policy_decision (
              decision_id, tenant_id, run_id, candidate_id, ordinal, decision,
              sensitivity_action, effective_scope, reason_codes, policy_version, decided_at
          ) VALUES (?, ?, ?, ?, 0, 'REMEMBER', 'NONE', 'USER',
                    ARRAY['POLICY_ACCEPTED'], 'write-policy.v1', ?)
          """,
          UUID.randomUUID(),
          tenantId,
          runId,
          candidateId,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.memory_lineage (
              memory_id, tenant_id, user_id, agent_id, memory_type, subject_kind,
              subject_label, predicate, predicate_cardinality, lifecycle_state,
              lock_version, created_at, updated_at
          ) VALUES (?, ?, ?, ?, 'SEMANTIC', 'USER', ?, 'residence', 'SINGLE',
                    'ACTIVE', 1, ?, ?)
          """,
          memoryId,
          tenantId,
          userId,
          agentId,
          userId,
          now,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.memory_version (
              version_id, tenant_id, memory_id, version_number, candidate_id, content_state,
              value_json, normalized_content, importance, confidence, content_fingerprint,
              extractor_version, prompt_version, policy_version, model_version, schema_version,
              transaction_time
          ) VALUES (?, ?, ?, 1, ?, 'AVAILABLE', '"Hangzhou"'::jsonb, ?, 0.8, 0.9,
                    decode('face', 'hex'), 'extractor.v1', 'prompt.v1', 'write-policy.v1',
                    'model.v1', 'memory-candidate.v1', ?)
          """,
          versionId,
          tenantId,
          memoryId,
          candidateId,
          RETAINED_CONTENT,
          now);
      execute(
          connection,
          """
          INSERT INTO memos.memory_source (
              tenant_id, memory_id, version_id, source_event_id, extraction_run_id,
              candidate_id, policy_version, derivation_role, evidence_ordinal,
              evidence_start, evidence_end, created_at
          ) VALUES (?, ?, ?, ?, ?, ?, 'write-policy.v1', 'EXTRACTED', 0, 0, 12, ?)
          """,
          tenantId,
          memoryId,
          versionId,
          sourceEventId,
          runId,
          candidateId,
          now);
      insertTransition(connection, tenantId, memoryId, transitionId, candidateId, versionId, now);
      execute(
          connection,
          """
          INSERT INTO memos.memory_status_change (
              tenant_id, transition_id, change_ordinal, memory_id, version_id,
              from_status, to_status
          ) VALUES (?, ?, 0, ?, ?, NULL, 'CURRENT')
          """,
          tenantId,
          transitionId,
          memoryId,
          versionId);
      execute(connection, "SELECT memos.rebuild_memory_current_state(?, ?)", tenantId, memoryId);
      execute(
          connection,
          """
          INSERT INTO memos.outbox_job (
              job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
              semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
              replay_count, next_attempt_at, payload_reference, trace_id, created_at, updated_at
          ) VALUES (?, ?, ?, 'PROJECTION_BUILD', 'MEMORY_TRANSITION', ?, ?,
                    'projection-v1', 'deterministic-hashing-64-v1', 'PENDING', 0, 3, 0,
                    ?, ?, 'trace-projection', ?, ?)
          """,
          projectionJobId,
          tenantId,
          sourceEventId,
          transitionId,
          "projection/" + transitionId,
          now,
          sourceEventId,
          now,
          now);
      insertProjection(
          connection,
          tenantId,
          userId,
          agentId,
          memoryId,
          versionId,
          transitionId,
          sourceEventId,
          projectionJobId,
          now);
      connection.commit();
    }
    return new Fixture(
        tenantId,
        userId,
        agentId,
        sourceEventId,
        sourceJobId,
        runId,
        candidateId,
        memoryId,
        versionId,
        transitionId,
        projectionJobId);
  }

  private static void insertTransition(
      Connection connection,
      String tenantId,
      UUID memoryId,
      UUID transitionId,
      UUID candidateId,
      UUID versionId,
      OffsetDateTime now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO memos.memory_state_transition (
                transition_id, tenant_id, memory_id, transition_sequence, operation,
                caused_by_candidate_id, related_version_ids, reason, actor_type, actor_id,
                transition_source, policy_version, transaction_time
            ) VALUES (?, ?, ?, 1, 'CREATE', ?, ?, 'FIRST_ASSERTION', 'WORKER',
                      'fixture-worker', 'CANDIDATE_MATERIALIZATION', 'write-policy.v1', ?)
            """)) {
      statement.setObject(1, transitionId);
      statement.setString(2, tenantId);
      statement.setObject(3, memoryId);
      statement.setObject(4, candidateId);
      statement.setArray(5, connection.createArrayOf("uuid", new UUID[] {versionId}));
      statement.setObject(6, now);
      statement.executeUpdate();
    }
  }

  private static void insertProjection(
      Connection connection,
      String tenantId,
      String userId,
      String agentId,
      UUID memoryId,
      UUID versionId,
      UUID transitionId,
      UUID sourceEventId,
      UUID projectionJobId,
      OffsetDateTime now)
      throws SQLException {
    String embedding = "[1," + "0,".repeat(62) + "0]";
    execute(
        connection,
        """
        INSERT INTO memos.memory_search_projection (
            tenant_id, user_id, agent_id, memory_id, version_id, memory_type,
            subject_kind, subject_label, predicate, truth_status, normalized_content,
            recorded_at, source_event_ids, embedding_model_version, embedding_dimensions,
            embedding, projection_policy_version, transition_id, transition_sequence,
            projected_at
        ) VALUES (?, ?, ?, ?, ?, 'SEMANTIC', 'USER', ?, 'residence', 'CURRENT', ?,
                  ?, ARRAY[?]::uuid[], 'deterministic-hashing-64-v1', 64, ?::vector,
                  'projection-v1', ?, 1, ?)
        """,
        tenantId,
        userId,
        agentId,
        memoryId,
        versionId,
        userId,
        RETAINED_CONTENT,
        now,
        sourceEventId,
        embedding,
        transitionId,
        now);
    execute(
        connection,
        """
        INSERT INTO memos.memory_projection_checkpoint (
            tenant_id, user_id, agent_id, memory_id, transition_id, transition_sequence,
            projection_policy_version, embedding_model_version, source_job_id,
            projected_version_count, projected_at
        ) VALUES (?, ?, ?, ?, ?, 1, 'projection-v1', 'deterministic-hashing-64-v1',
                  ?, 1, ?)
        """,
        tenantId,
        userId,
        agentId,
        memoryId,
        transitionId,
        projectionJobId,
        now);
  }

  private static void execute(Connection connection, String sql, Object... arguments)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      statement.execute();
    }
  }

  private record Fixture(
      String tenantId,
      String userId,
      String agentId,
      UUID sourceEventId,
      UUID sourceJobId,
      UUID runId,
      UUID candidateId,
      UUID memoryId,
      UUID versionId,
      UUID transitionId,
      UUID projectionJobId) {
    private MemoryScope scope() {
      return new MemoryScope(tenantId, userId, agentId);
    }
  }
}
