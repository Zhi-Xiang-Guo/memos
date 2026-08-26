package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.adapters.json.JacksonPayloadCanonicalizer;
import dev.memos.adapters.system.UuidIngestionIdentifierGenerator;
import dev.memos.governance.MemoryScope;
import dev.memos.ingestion.ActorType;
import dev.memos.ingestion.IngestionConflict;
import dev.memos.ingestion.IngestionConflictException;
import dev.memos.ingestion.IngestionDisposition;
import dev.memos.ingestion.IngestionTelemetry;
import dev.memos.ingestion.SourceIngestionCommand;
import dev.memos.ingestion.SourceIngestionService;
import dev.memos.ingestion.SourceType;
import dev.memos.ingestion.TrustLevel;
import dev.memos.materialization.ClaimRequest;
import dev.memos.materialization.ExponentialBackoffPolicy;
import dev.memos.materialization.FencedUpdateResult;
import dev.memos.materialization.JobErrorClass;
import dev.memos.materialization.JobHandlingException;
import dev.memos.materialization.JobState;
import dev.memos.materialization.MaterializationJobHandler;
import dev.memos.materialization.OutboxWorkerService;
import dev.memos.materialization.OutboxWorkerTelemetry;
import dev.memos.materialization.ReplayResult;
import dev.memos.materialization.WorkerId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class FeatureOnePostgresIntegrationTest {
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

  private JdbcTemplate jdbc;
  private JdbcSourceIngestionStore ingestionStore;
  private JdbcMaterializationJobStore jobStore;

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

  @BeforeEach
  void setUp() {
    var dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    ingestionStore = new JdbcSourceIngestionStore(jdbc, transactions);
    jobStore = new JdbcMaterializationJobStore(jdbc, transactions);
    jdbc.execute(
        "TRUNCATE memos.materialization_result, memos.outbox_job, memos.source_event CASCADE");
  }

  @Test
  void ingestionIsAtomicIdempotentAndConflictAware() {
    var service = ingestionService();
    var command = command("tenant-a", "source-a", "key-a", "{\"content\":\"alpha\"}");

    var accepted = service.ingest(command);
    var idempotentReplay = service.ingest(command);
    var sourceReplay =
        service.ingest(command("tenant-a", "source-a", "key-b", "{\"content\":\"alpha\"}"));

    assertThat(accepted.disposition()).isEqualTo(IngestionDisposition.ACCEPTED);
    assertThat(idempotentReplay.disposition()).isEqualTo(IngestionDisposition.IDEMPOTENT_REPLAY);
    assertThat(sourceReplay.disposition()).isEqualTo(IngestionDisposition.SOURCE_REPLAY);
    assertThat(idempotentReplay.sourceEventId()).isEqualTo(accepted.sourceEventId());
    assertThat(sourceReplay.materializationJobId()).isEqualTo(accepted.materializationJobId());
    assertThat(count("memos.source_event")).isEqualTo(1);
    assertThat(count("memos.outbox_job")).isEqualTo(1);

    assertConflict(
        IngestionConflict.IDEMPOTENCY_KEY_REUSED,
        () ->
            service.ingest(
                command("tenant-a", "different-source", "key-a", "{\"content\":\"beta\"}")));
    assertConflict(
        IngestionConflict.SOURCE_ID_REUSED,
        () ->
            service.ingest(
                command("tenant-a", "source-a", "different-key", "{\"content\":\"beta\"}")));
    assertThat(count("memos.source_event")).isEqualTo(1);
    assertThat(count("memos.outbox_job")).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateRequestsCommitOneSourceAndIntent() throws Exception {
    var service = ingestionService();
    var command = command("tenant-a", "source-a", "key-a", "{\"content\":\"alpha\"}");
    try (var executor = Executors.newFixedThreadPool(12)) {
      List<Callable<String>> calls = new ArrayList<>();
      for (int index = 0; index < 24; index++) {
        calls.add(() -> service.ingest(command).sourceEventId().toString());
      }
      var futures = executor.invokeAll(calls);
      var ids = new HashSet<String>();
      for (var future : futures) {
        ids.add(future.get());
      }
      assertThat(ids).hasSize(1);
    }
    assertThat(count("memos.source_event")).isEqualTo(1);
    assertThat(count("memos.outbox_job")).isEqualTo(1);
  }

  @Test
  void outboxFailureRollsBackSourceButCommittedSourceSurvivesWorkerOutage() {
    jdbc.execute(
        """
        CREATE FUNCTION memos.fail_outbox_insert() RETURNS trigger AS $$
        BEGIN RAISE EXCEPTION 'injected outbox failure'; END;
        $$ LANGUAGE plpgsql
        """);
    jdbc.execute(
        """
        CREATE TRIGGER fail_outbox BEFORE INSERT ON memos.outbox_job
        FOR EACH ROW EXECUTE FUNCTION memos.fail_outbox_insert()
        """);

    assertThatThrownBy(
            () ->
                ingestionService()
                    .ingest(command("tenant-a", "source-a", "key-a", "{\"content\":\"x\"}")))
        .isInstanceOf(DataAccessException.class);
    assertThat(count("memos.source_event")).isZero();
    assertThat(count("memos.outbox_job")).isZero();

    jdbc.execute("DROP TRIGGER fail_outbox ON memos.outbox_job");
    jdbc.execute("DROP FUNCTION memos.fail_outbox_insert()");
    ingestionService().ingest(command("tenant-a", "source-a", "key-a", "{\"content\":\"x\"}"));
    assertThat(jdbc.queryForObject("SELECT state FROM memos.outbox_job", String.class))
        .isEqualTo("PENDING");
  }

  @Test
  void claimRunsHandlerOutsideTransactionAndCompletionCreatesOneLogicalEffect() {
    var receipt =
        ingestionService().ingest(command("tenant-a", "source-a", "key-a", "{\"content\":\"x\"}"));
    var calls = new AtomicInteger();
    MaterializationJobHandler handler =
        job -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          calls.incrementAndGet();
        };
    var worker = worker("worker-a", handler, 8, Duration.ofSeconds(30));

    var summary = worker.runOnce();

    assertThat(summary.claimed()).isEqualTo(1);
    assertThat(summary.succeeded()).isEqualTo(1);
    assertThat(calls).hasValue(1);
    assertThat(count("memos.materialization_result")).isEqualTo(1);
    assertThat(worker.runOnce().claimed()).isZero();
    assertThat(
            jobStore
                .find(
                    new MemoryScope("tenant-a", "user-a", "agent-a"),
                    new dev.memos.materialization.JobId(receipt.materializationJobId().value()))
                .orElseThrow()
                .state())
        .isEqualTo(JobState.SUCCEEDED);
  }

  @Test
  void expiredLeaseIsReclaimedAndStaleWorkerIsFenced() {
    ingestionService().ingest(command("tenant-a", "source-a", "key-a", "{\"content\":\"x\"}"));
    var first =
        jobStore
            .claim(
                new ClaimRequest(
                    new WorkerId("worker-a"), 1, Instant.now(), Duration.ofSeconds(30)))
            .getFirst();
    expireLease(first.jobId().value());

    var second =
        jobStore
            .claim(
                new ClaimRequest(
                    new WorkerId("worker-b"), 1, Instant.now(), Duration.ofSeconds(30)))
            .getFirst();

    assertThat(second.attempt()).isEqualTo(2);
    assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
    assertThat(jobStore.markSucceeded(first.fence(), Instant.now()))
        .isEqualTo(FencedUpdateResult.LEASE_LOST);
    assertThat(
            jobStore.scheduleRetry(
                first.fence(), new JobErrorClass("STALE"), Instant.now(), Instant.now()))
        .isEqualTo(FencedUpdateResult.LEASE_LOST);
    assertThat(jobStore.markSucceeded(second.fence(), Instant.now()))
        .isEqualTo(FencedUpdateResult.UPDATED);
    assertThat(count("memos.materialization_result")).isEqualTo(1);
  }

  @Test
  void transientAndPoisonFailuresReachObservableRetryAndDeadStates() {
    var transientReceipt =
        ingestionService()
            .ingest(command("tenant-a", "source-transient", "key-t", "{\"content\":\"x\"}"));
    var calls = new AtomicInteger();
    var transientWorker =
        worker(
            "worker-a",
            job -> {
              if (calls.getAndIncrement() == 0) {
                throw JobHandlingException.transientFailure("PROVIDER_TIMEOUT");
              }
            },
            1,
            Duration.ofSeconds(30));
    assertThat(transientWorker.runOnce().retriesScheduled()).isEqualTo(1);
    jdbc.update(
        "UPDATE memos.outbox_job SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE job_id = ?",
        transientReceipt.materializationJobId().value());
    assertThat(transientWorker.runOnce().succeeded()).isEqualTo(1);

    var poisonReceipt =
        ingestionService()
            .ingest(command("tenant-a", "source-poison", "key-p", "{\"content\":\"x\"}"));
    var poisonWorker =
        worker(
            "worker-b",
            job -> {
              throw JobHandlingException.permanentFailure("INVALID_PAYLOAD");
            },
            1,
            Duration.ofSeconds(30));
    assertThat(poisonWorker.runOnce().dead()).isEqualTo(1);
    var poison =
        jobStore
            .find(
                new MemoryScope("tenant-a", "user-a", "agent-a"),
                new dev.memos.materialization.JobId(poisonReceipt.materializationJobId().value()))
            .orElseThrow();
    assertThat(poison.state()).isEqualTo(JobState.DEAD);
    assertThat(poison.errorClass().value()).isEqualTo("INVALID_PAYLOAD");
  }

  @Test
  void expiredClaimAtAttemptLimitMovesToDeadForRestartRecovery() {
    var receipt =
        ingestionService().ingest(command("tenant-a", "source-a", "key-a", "{\"content\":\"x\"}"));
    jdbc.update(
        "UPDATE memos.outbox_job SET max_attempts = 1 WHERE job_id = ?",
        receipt.materializationJobId().value());
    var claimed =
        jobStore
            .claim(
                new ClaimRequest(
                    new WorkerId("worker-that-crashes"), 1, Instant.now(), Duration.ofSeconds(30)))
            .getFirst();
    expireLease(claimed.jobId().value());

    assertThat(jobStore.deadLetterExpiredExhaustedJobs(Instant.now())).isEqualTo(1);
    var job =
        jobStore
            .find(new MemoryScope("tenant-a", "user-a", "agent-a"), claimed.jobId())
            .orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.DEAD);
    assertThat(job.errorClass().value()).isEqualTo("LEASE_EXPIRED_ATTEMPTS_EXHAUSTED");
  }

  @Test
  void replayKeepsSemanticIdentityAndScopeIsolation() {
    var receipt =
        ingestionService().ingest(command("tenant-a", "source-a", "key-a", "{\"content\":\"x\"}"));
    var jobId = new dev.memos.materialization.JobId(receipt.materializationJobId().value());
    var scope = new MemoryScope("tenant-a", "user-a", "agent-a");
    var claimed =
        jobStore
            .claim(
                new ClaimRequest(
                    new WorkerId("worker-a"), 1, Instant.now(), Duration.ofSeconds(30)))
            .getFirst();
    assertThat(jobStore.markDead(claimed.fence(), new JobErrorClass("POISON"), Instant.now()))
        .isEqualTo(FencedUpdateResult.UPDATED);
    String semanticKey =
        jdbc.queryForObject(
            "SELECT semantic_job_key FROM memos.outbox_job WHERE job_id = ?",
            String.class,
            jobId.value());

    assertThat(jobStore.find(new MemoryScope("tenant-a", "other-user", "agent-a"), jobId))
        .isEmpty();
    assertThat(
            jobStore.replay(
                new MemoryScope("tenant-a", "other-user", "agent-a"), jobId, Instant.now()))
        .isEqualTo(ReplayResult.NOT_FOUND);
    assertThat(jobStore.replay(scope, jobId, Instant.now())).isEqualTo(ReplayResult.REPLAYED);
    var replayed = jobStore.find(scope, jobId).orElseThrow();
    assertThat(replayed.state()).isEqualTo(JobState.PENDING);
    assertThat(replayed.attempt()).isZero();
    assertThat(replayed.replayCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT semantic_job_key FROM memos.outbox_job WHERE job_id = ?",
                String.class,
                jobId.value()))
        .isEqualTo(semanticKey);
    var replayClaim =
        jobStore
            .claim(
                new ClaimRequest(
                    new WorkerId("worker-b"), 1, Instant.now(), Duration.ofSeconds(30)))
            .getFirst();
    assertThat(jobStore.markSucceeded(replayClaim.fence(), Instant.now()))
        .isEqualTo(FencedUpdateResult.UPDATED);
    assertThat(count("memos.materialization_result")).isEqualTo(1);
    assertThat(jobStore.replay(scope, jobId, Instant.now())).isEqualTo(ReplayResult.NOT_REPLAYABLE);
  }

  @Test
  void multipleWorkersClaimDistinctJobsWithoutLoss() throws Exception {
    for (int index = 0; index < 20; index++) {
      ingestionService()
          .ingest(command("tenant-a", "source-" + index, "key-" + index, "{\"content\":\"x\"}"));
    }
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Callable<List<dev.memos.materialization.ClaimedJob>>> claims =
          List.of(
              () ->
                  jobStore.claim(
                      new ClaimRequest(
                          new WorkerId("worker-a"), 20, Instant.now(), Duration.ofMinutes(1))),
              () ->
                  jobStore.claim(
                      new ClaimRequest(
                          new WorkerId("worker-b"), 20, Instant.now(), Duration.ofMinutes(1))));
      var futures = executor.invokeAll(claims);
      var identities = new HashSet<UUID>();
      int total = 0;
      for (var future : futures) {
        var jobs = future.get();
        total += jobs.size();
        jobs.forEach(job -> identities.add(job.jobId().value()));
      }
      assertThat(total).isEqualTo(20);
      assertThat(identities).hasSize(20);
    }
  }

  private SourceIngestionService ingestionService() {
    var mapper =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    return new SourceIngestionService(
        Clock.systemUTC(),
        new UuidIngestionIdentifierGenerator(),
        new JacksonPayloadCanonicalizer(mapper),
        ingestionStore,
        IngestionTelemetry.NOOP,
        "ingestion-v1",
        "deterministic-fake-v1",
        5);
  }

  private OutboxWorkerService worker(
      String workerId, MaterializationJobHandler handler, int batchSize, Duration leaseDuration) {
    return new OutboxWorkerService(
        Clock.systemUTC(),
        jobStore,
        handler,
        new ExponentialBackoffPolicy(Duration.ofMillis(1), Duration.ofSeconds(1)),
        OutboxWorkerTelemetry.NOOP,
        new WorkerId(workerId),
        batchSize,
        leaseDuration);
  }

  private static SourceIngestionCommand command(
      String tenantId, String sourceId, String key, String payload) {
    return new SourceIngestionCommand(
        new MemoryScope(tenantId, "user-a", "agent-a"),
        sourceId,
        "session-a",
        key,
        ActorType.USER,
        SourceType.CONVERSATION_MESSAGE,
        TrustLevel.DIRECT_USER,
        Instant.parse("2026-08-26T12:00:00Z"),
        payload,
        "trace-test");
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private void expireLease(UUID jobId) {
    jdbc.update(
        """
        UPDATE memos.outbox_job
           SET created_at = clock_timestamp() - interval '3 minutes',
               updated_at = clock_timestamp() - interval '2 minutes',
               lease_expires_at = clock_timestamp() - interval '1 minute'
         WHERE job_id = ?
        """,
        jobId);
  }

  private static void assertConflict(IngestionConflict expected, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(IngestionConflictException.class)
        .extracting(exception -> ((IngestionConflictException) exception).reason())
        .isEqualTo(expected);
  }
}
