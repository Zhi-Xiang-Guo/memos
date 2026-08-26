package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.governance.MemoryScope;
import dev.memos.governance.PolicyDecision;
import dev.memos.governance.SensitivityAction;
import dev.memos.governance.WritePolicyReason;
import dev.memos.materialization.CandidateCommitRecord;
import dev.memos.materialization.CandidateContentState;
import dev.memos.materialization.CandidateId;
import dev.memos.materialization.CandidatePolicyDecisionRecord;
import dev.memos.materialization.CandidateQuarantineRecord;
import dev.memos.materialization.ClaimRequest;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.CommitExtractionSuccess;
import dev.memos.materialization.DownstreamMaterializationIntent;
import dev.memos.materialization.ExtractionAttemptId;
import dev.memos.materialization.ExtractionAttemptStartResult;
import dev.memos.materialization.ExtractionCommitResult;
import dev.memos.materialization.ExtractionProviderIdentity;
import dev.memos.materialization.ExtractionRunId;
import dev.memos.materialization.JobId;
import dev.memos.materialization.JobType;
import dev.memos.materialization.LeaseToken;
import dev.memos.materialization.ProviderCallMetadata;
import dev.memos.materialization.ProviderTokenUsage;
import dev.memos.materialization.QuarantineId;
import dev.memos.materialization.SemanticJobKey;
import dev.memos.materialization.StartExtractionAttempt;
import dev.memos.materialization.WorkerId;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcExtractionCommitStoreIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector:0.8.6-pg18@sha256:2ba9ca5f2e7daa0f0e7723cba1ee9167bab54efd3640516a44ac1a928dd67e7a")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("memos")
          .withUsername("memos")
          .withPassword("memos-test")
          .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(1)));

  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema("public")
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .load();
    assertThat(flyway.migrate().success).isTrue();
    flyway.validate();
  }

  @Test
  void commitsAuthorityAndClaimableDownstreamIntentInOneFencedTransaction() {
    Fixture fixture = fixture("tenant-success");
    JdbcExtractionCommitStore store = new JdbcExtractionCommitStore(jdbc, transactions);
    ExtractionAttemptId attemptId = new ExtractionAttemptId(UUID.randomUUID());
    Instant startedAt = Instant.now();
    assertThat(store.startAttempt(start(attemptId, fixture.job(), startedAt)))
        .isEqualTo(ExtractionAttemptStartResult.STARTED);

    ExtractionRunId runId = new ExtractionRunId(UUID.randomUUID());
    CandidateId candidateId = new CandidateId(UUID.randomUUID());
    CommitExtractionSuccess command = success(attemptId, runId, candidateId, fixture.job());
    assertThat(store.commitSuccess(command)).isEqualTo(ExtractionCommitResult.COMMITTED);
    assertThat(store.commitSuccess(command)).isEqualTo(ExtractionCommitResult.ALREADY_COMMITTED);

    assertThat(count("extraction_run", "run_id", runId.value())).isEqualTo(1);
    assertThat(count("memory_candidate", "candidate_id", candidateId.value())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM memos.memory_candidate
                 WHERE run_id = ? AND content_state = 'ERASED'
                   AND proposed_decision IS NULL AND subject_kind IS NULL
                   AND predicate IS NULL AND value_json IS NULL
                   AND normalized_content IS NULL AND memory_type IS NULL
                   AND importance IS NULL AND confidence IS NULL
                   AND relation_hints_json IS NULL AND content_fingerprint IS NULL
                """,
                Integer.class,
                runId.value()))
        .isEqualTo(1);
    assertThat(count("candidate_policy_decision", "candidate_id", candidateId.value()))
        .isEqualTo(1);
    assertThat(count("materialization_result", "job_id", fixture.job().jobId().value()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM memos.outbox_job WHERE job_id = ?",
                String.class,
                fixture.job().jobId().value()))
        .isEqualTo("SUCCEEDED");

    var sourceWorkerClaims =
        new JdbcMaterializationJobStore(jdbc, transactions)
            .claim(
                new ClaimRequest(
                    new WorkerId("downstream-worker"), 1, Instant.now(), Duration.ofMinutes(1)));
    assertThat(sourceWorkerClaims).isEmpty();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT state FROM memos.outbox_job
                 WHERE tenant_id = ? AND job_type = 'CANDIDATE_MATERIALIZATION'
                """,
                String.class,
                fixture.job().scope().tenantId()))
        .isEqualTo("PENDING");

    String extractionText =
        jdbc.queryForObject(
            """
            SELECT concat_ws('|', run.semantic_run_key, candidate.normalized_content,
                              decision.reason_codes::text, quarantine.reason_code)
              FROM memos.extraction_run run
              JOIN memos.memory_candidate candidate USING (tenant_id, run_id)
              JOIN memos.candidate_policy_decision decision
                ON decision.tenant_id = candidate.tenant_id
               AND decision.candidate_id = candidate.candidate_id
              LEFT JOIN memos.memory_quarantine quarantine
                ON quarantine.tenant_id = run.tenant_id AND quarantine.run_id = run.run_id
             WHERE run.run_id = ? AND candidate.content_state = 'AVAILABLE'
            """,
            String.class,
            runId.value());
    assertThat(extractionText).doesNotContain("RAW_PROVIDER_SECRET_MARKER");
  }

  @Test
  void rollsBackRunCandidatesDownstreamAndCompletionWhenDownstreamInsertFails() {
    Fixture fixture = fixture("tenant-fault");
    JdbcExtractionCommitStore store = new JdbcExtractionCommitStore(jdbc, transactions);
    ExtractionAttemptId attemptId = new ExtractionAttemptId(UUID.randomUUID());
    assertThat(store.startAttempt(start(attemptId, fixture.job(), Instant.now())))
        .isEqualTo(ExtractionAttemptStartResult.STARTED);
    jdbc.execute(
        """
        CREATE FUNCTION memos.test_reject_downstream() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.aggregate_type = 'EXTRACTION_RUN' THEN
            RAISE EXCEPTION 'injected downstream failure';
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER test_reject_downstream
          BEFORE INSERT ON memos.outbox_job
          FOR EACH ROW EXECUTE FUNCTION memos.test_reject_downstream();
        """);
    ExtractionRunId runId = new ExtractionRunId(UUID.randomUUID());
    try {
      assertThatThrownBy(
              () ->
                  store.commitSuccess(
                      success(attemptId, runId, new CandidateId(UUID.randomUUID()), fixture.job())))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("injected downstream failure");
    } finally {
      jdbc.execute("DROP TRIGGER test_reject_downstream ON memos.outbox_job");
      jdbc.execute("DROP FUNCTION memos.test_reject_downstream()");
    }

    assertThat(count("extraction_run", "run_id", runId.value())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM memos.extraction_attempt WHERE attempt_id = ?",
                String.class,
                attemptId.value()))
        .isEqualTo("STARTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM memos.outbox_job WHERE job_id = ?",
                String.class,
                fixture.job().jobId().value()))
        .isEqualTo("CLAIMED");
  }

  @Test
  void staleLeaseCannotCreateAnyAuthoritativeExtractionRows() {
    Fixture fixture = fixture("tenant-stale");
    JdbcExtractionCommitStore store = new JdbcExtractionCommitStore(jdbc, transactions);
    ExtractionAttemptId attemptId = new ExtractionAttemptId(UUID.randomUUID());
    assertThat(store.startAttempt(start(attemptId, fixture.job(), Instant.now())))
        .isEqualTo(ExtractionAttemptStartResult.STARTED);
    jdbc.update(
        """
        UPDATE memos.outbox_job
           SET lease_token = gen_random_uuid(), updated_at = clock_timestamp()
         WHERE job_id = ?
        """,
        fixture.job().jobId().value());

    ExtractionRunId runId = new ExtractionRunId(UUID.randomUUID());
    assertThat(
            store.commitSuccess(
                success(attemptId, runId, new CandidateId(UUID.randomUUID()), fixture.job())))
        .isEqualTo(ExtractionCommitResult.LEASE_LOST);
    assertThat(count("extraction_run", "run_id", runId.value())).isZero();
  }

  @Test
  void concurrentDuplicateDeliveryCommitsExactlyOneRunAndOneDownstreamIntent() throws Exception {
    Fixture fixture = fixture("tenant-concurrent");
    JdbcExtractionCommitStore store = new JdbcExtractionCommitStore(jdbc, transactions);
    ExtractionAttemptId attemptId = new ExtractionAttemptId(UUID.randomUUID());
    assertThat(store.startAttempt(start(attemptId, fixture.job(), Instant.now())))
        .isEqualTo(ExtractionAttemptStartResult.STARTED);
    ExtractionRunId runId = new ExtractionRunId(UUID.randomUUID());
    CommitExtractionSuccess command =
        success(attemptId, runId, new CandidateId(UUID.randomUUID()), fixture.job());
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return store.commitSuccess(command);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return store.commitSuccess(command);
              });
      start.countDown();
      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder(
              ExtractionCommitResult.COMMITTED, ExtractionCommitResult.ALREADY_COMMITTED);
    }
    assertThat(count("extraction_run", "run_id", runId.value())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM memos.outbox_job
                 WHERE tenant_id = ? AND job_type = 'CANDIDATE_MATERIALIZATION'
                """,
                Integer.class,
                fixture.job().scope().tenantId()))
        .isEqualTo(1);
  }

  private static StartExtractionAttempt start(
      ExtractionAttemptId attemptId, ClaimedJob job, Instant startedAt) {
    return new StartExtractionAttempt(
        attemptId,
        job,
        new ExtractionProviderIdentity(
            "deterministic-fake", "fake-model.v1", "prompt.v1", "memory-candidate.v1"),
        "write-policy.v1",
        startedAt);
  }

  private static CommitExtractionSuccess success(
      ExtractionAttemptId attemptId,
      ExtractionRunId runId,
      CandidateId candidateId,
      ClaimedJob job) {
    var proposal =
        new MemoryCandidateProposal(
            ProposalDecision.REMEMBER,
            MemoryType.SEMANTIC,
            new CandidateSubject(SubjectKind.USER, "user-1"),
            "preference.drink",
            new TextCandidateValue("tea"),
            "User prefers tea.",
            null,
            null,
            0.8,
            0.9,
            Set.of(SensitivityCategory.NONE),
            List.of());
    var policy =
        new CandidatePolicyDecisionRecord(
            PolicyDecision.REMEMBER,
            SensitivityAction.NONE,
            List.of(WritePolicyReason.POLICY_ACCEPTED),
            "write-policy.v1");
    CandidateId erasedCandidateId = new CandidateId(UUID.randomUUID());
    var erasedPolicy =
        new CandidatePolicyDecisionRecord(
            PolicyDecision.IGNORE,
            SensitivityAction.REJECT,
            List.of(WritePolicyReason.SECRET_REJECTED),
            "write-policy.v1");
    return new CommitExtractionSuccess(
        attemptId,
        runId,
        job,
        "extract/" + job.sourceEventId() + "/memory-candidate.v1",
        new ProviderCallMetadata(
            "deterministic-fake",
            "fake-model.v1",
            "prompt.v1",
            "memory-candidate.v1",
            "provider-call-1",
            new ProviderTokenUsage(12, 5),
            Duration.ofMillis(20)),
        "write-policy.v1",
        List.of(
            new CandidateCommitRecord(
                candidateId, 0, CandidateContentState.AVAILABLE, proposal, policy),
            new CandidateCommitRecord(
                erasedCandidateId, 1, CandidateContentState.ERASED, null, erasedPolicy)),
        List.of(
            new CandidateQuarantineRecord(
                new QuarantineId(UUID.randomUUID()),
                erasedCandidateId,
                1,
                WritePolicyReason.SECRET_REJECTED)),
        new DownstreamMaterializationIntent(
            new SemanticJobKey("candidate-materialization/" + runId.value()),
            JobType.CANDIDATE_MATERIALIZATION),
        Instant.now().plusMillis(25));
  }

  private static Fixture fixture(String tenantPrefix) {
    String tenantId = tenantPrefix + "-" + UUID.randomUUID();
    UUID sourceEventId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        INSERT INTO memos.source_event (
            source_event_id, tenant_id, user_id, agent_id, source_id, session_id,
            idempotency_key, actor_type, source_type, trust_level, occurred_at,
            received_at, payload, request_fingerprint, deletion_state, trace_id, created_at
        ) VALUES (?, ?, 'user-1', 'agent-1', ?, 'session-1', ?, 'USER',
                  'CONVERSATION_MESSAGE', 'DIRECT_USER', ?, ?, '{"content":"tea"}'::jsonb,
                  decode('beef', 'hex'), 'ACTIVE', 'trace-test', ?)
        """,
        sourceEventId,
        tenantId,
        "source-" + sourceEventId,
        "idempotency-" + sourceEventId,
        now.minusSeconds(1),
        now,
        now);
    jdbc.update(
        """
        INSERT INTO memos.outbox_job (
            job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
            semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
            replay_count, next_attempt_at, payload_reference, lease_owner, lease_token,
            lease_expires_at, trace_id, created_at, updated_at
        ) VALUES (?, ?, ?, 'MATERIALIZE_SOURCE', 'SOURCE_EVENT', ?, ?, 'write-policy.v1',
                  'fake-model.v1', 'CLAIMED', 1, 3, 0, NULL, ?, 'worker-a', ?, ?,
                  'trace-test', ?, ?)
        """,
        jobId,
        tenantId,
        sourceEventId,
        sourceEventId,
        "materialize/" + sourceEventId,
        sourceEventId,
        leaseToken,
        now.plusMinutes(5),
        now,
        now);
    return new Fixture(
        new ClaimedJob(
            new JobId(jobId),
            JobType.MATERIALIZE_SOURCE,
            new MemoryScope(tenantId, "user-1", "agent-1"),
            sourceEventId,
            new SemanticJobKey("materialize/" + sourceEventId),
            "write-policy.v1",
            "fake-model.v1",
            1,
            3,
            new WorkerId("worker-a"),
            new LeaseToken(leaseToken),
            now.plusMinutes(5).toInstant(),
            "trace-test"));
  }

  private static int count(String table, String column, UUID id) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM memos." + table + " WHERE " + column + " = ?", Integer.class, id);
    return count == null ? 0 : count;
  }

  private record Fixture(ClaimedJob job) {}
}
