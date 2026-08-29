package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.adapters.system.RandomTemporalIdentityGenerator;
import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.domain.temporal.AssertionDerivationRole;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MaterializeCandidateCommand;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.NormalizedAssertionDeduplication;
import dev.memos.domain.temporal.PredicateCardinality;
import dev.memos.domain.temporal.TemporalTransitionPlanner;
import dev.memos.domain.temporal.TransitionActor;
import dev.memos.domain.temporal.TransitionContext;
import dev.memos.domain.temporal.TransitionSource;
import dev.memos.governance.MemoryScope;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.CommitProjectionBuild;
import dev.memos.materialization.CommitTemporalMaterialization;
import dev.memos.materialization.CorrectionSelection;
import dev.memos.materialization.InvalidationSelection;
import dev.memos.materialization.JobId;
import dev.memos.materialization.JobType;
import dev.memos.materialization.LeaseToken;
import dev.memos.materialization.PlannedCandidateMaterialization;
import dev.memos.materialization.ProjectedVersionBuild;
import dev.memos.materialization.ProjectionBuildPlan;
import dev.memos.materialization.ProjectionCommitResult;
import dev.memos.materialization.ProjectionEmbedding;
import dev.memos.materialization.SemanticJobKey;
import dev.memos.materialization.TemporalMaterializationCommitResult;
import dev.memos.materialization.TemporalMutationDisposition;
import dev.memos.materialization.TemporalMutationException;
import dev.memos.materialization.TemporalMutationFailureKind;
import dev.memos.materialization.WorkerId;
import dev.memos.retrieval.CandidateSource;
import dev.memos.retrieval.CandidateStoreQuery;
import dev.memos.retrieval.EmbeddingResult;
import dev.memos.retrieval.QueryIntent;
import dev.memos.retrieval.TemporalQueryIntent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TemporalMemoryMigrationTest {
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

  @Test
  void createsScopedAuthorityTablesWithoutAuditContentColumns() throws Exception {
    try (Connection connection = connection();
        var statement =
            connection.prepareStatement(
                """
                SELECT table_name, column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'memos'
                   AND table_name IN (
                       'memory_lineage', 'memory_version', 'memory_state_transition',
                       'memory_status_change', 'memory_source', 'memory_current_state',
                       'audit_event'
                   )
                 ORDER BY table_name, ordinal_position
                """);
        var result = statement.executeQuery()) {
      var tables = new ArrayList<String>();
      var auditColumns = new ArrayList<String>();
      while (result.next()) {
        tables.add(result.getString("table_name"));
        if (result.getString("table_name").equals("audit_event")) {
          auditColumns.add(result.getString("column_name"));
        }
      }
      assertThat(tables)
          .contains(
              "memory_lineage",
              "memory_version",
              "memory_state_transition",
              "memory_status_change",
              "memory_source",
              "memory_current_state",
              "audit_event");
      assertThat(auditColumns)
          .noneMatch(
              column ->
                  column.contains("content")
                      || column.contains("payload")
                      || column.contains("value")
                      || column.contains("fingerprint")
                      || column.contains("secret"));
    }
  }

  @Test
  void enforcesMonotonicImmutableScopedVersionsAndCompleteProvenance() throws Exception {
    Fixture fixture = fixture("tenant-invariants");
    UUID lineageId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    insertFirstAuthority(fixture, lineageId, versionId, UUID.randomUUID());

    assertSqlState(
        "55000",
        () ->
            execute(
                "UPDATE memos.memory_version SET normalized_content = 'mutated' WHERE version_id = ?",
                versionId));
    assertSqlState(
        "23514",
        () -> {
          try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            UUID candidateId = insertAdditionalCandidate(connection, fixture, 1);
            insertVersion(connection, fixture, lineageId, UUID.randomUUID(), 3, candidateId);
          }
        });

    assertThatThrownBy(
            () -> {
              try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                UUID missingProvenanceVersion = UUID.randomUUID();
                UUID candidateId = insertAdditionalCandidate(connection, fixture, 2);
                insertVersion(
                    connection, fixture, lineageId, missingProvenanceVersion, 2, candidateId);
                connection.commit();
              }
            })
        .isInstanceOf(PSQLException.class)
        .extracting(throwable -> ((PSQLException) throwable).getSQLState())
        .isEqualTo("23514");

    assertSqlState(
        "23514",
        () ->
            execute(
                """
                INSERT INTO memos.memory_source (
                    tenant_id, memory_id, version_id, source_event_id, extraction_run_id,
                    candidate_id, policy_version, derivation_role, evidence_ordinal,
                    created_at
                ) VALUES ('other-tenant', ?, ?, ?, ?, ?, 'write-policy.v1', 'EXTRACTED',
                          0, clock_timestamp())
                """,
                lineageId,
                versionId,
                fixture.sourceEventId(),
                fixture.extractionRunId(),
                fixture.candidateId()));

    UUID secondLineageId = UUID.randomUUID();
    UUID secondVersionId = UUID.randomUUID();
    UUID secondTransitionId = UUID.randomUUID();
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      UUID secondCandidateId = insertAdditionalCandidate(connection, fixture, 90);
      execute(
          connection,
          """
          INSERT INTO memos.memory_lineage (
              memory_id, tenant_id, user_id, agent_id, memory_type, subject_kind,
              subject_label, predicate, predicate_cardinality, lifecycle_state,
              lock_version, created_at, updated_at
          ) VALUES (?, ?, 'user-1', 'agent-1', 'SEMANTIC', 'USER', 'user-1',
                    'residence.secondary', 'SINGLE', 'ACTIVE', 1,
                    clock_timestamp(), clock_timestamp())
          """,
          secondLineageId,
          fixture.tenantId());
      insertVersion(connection, fixture, secondLineageId, secondVersionId, 1, secondCandidateId);
      insertSource(connection, fixture, secondLineageId, secondVersionId, 90, secondCandidateId);
      insertTransition(
          connection,
          fixture,
          secondLineageId,
          secondTransitionId,
          1,
          "CREATE",
          new UUID[] {secondVersionId},
          "FIRST_ASSERTION",
          secondCandidateId);
      insertStatusChange(
          connection,
          fixture.tenantId(),
          secondLineageId,
          secondTransitionId,
          0,
          secondVersionId,
          null,
          "CURRENT");
      execute(
          connection,
          "SELECT memos.rebuild_memory_current_state(?, ?)",
          fixture.tenantId(),
          secondLineageId);
      connection.commit();
    }
    assertSqlState(
        "23503",
        () ->
            execute(
                """
                UPDATE memos.memory_current_state
                   SET transition_id = ?, transition_change_ordinal = 0
                 WHERE tenant_id = ? AND memory_id = ? AND version_id = ?
                """,
                secondTransitionId,
                fixture.tenantId(),
                lineageId,
                versionId));

    UUID mutationRequestId = UUID.randomUUID();
    execute(
        """
        INSERT INTO memos.memory_mutation_request (
            mutation_request_id, tenant_id, user_id, agent_id, idempotency_key,
            request_fingerprint, mutation_type, memory_id, target_version_id,
            source_event_id, candidate_id, state, trace_id, created_at
        ) VALUES (?, ?, 'user-1', 'agent-1', 'cross-lineage-result',
                  decode('abcd', 'hex'), 'CORRECT', ?, ?, ?, ?, 'STARTED',
                  'trace-test', clock_timestamp())
        """,
        mutationRequestId,
        fixture.tenantId(),
        lineageId,
        versionId,
        fixture.sourceEventId(),
        fixture.candidateId());
    assertSqlState(
        "23503",
        () ->
            execute(
                """
                UPDATE memos.memory_mutation_request
                   SET state = 'COMPLETED', result_lock_version = 2,
                       result_version_id = ?, result_transition_id = ?,
                       completed_at = clock_timestamp()
                 WHERE mutation_request_id = ?
                """,
                secondVersionId,
                secondTransitionId,
                mutationRequestId));
    assertSqlState(
        "23503",
        () ->
            execute(
                """
                INSERT INTO memos.memory_mutation_request (
                    mutation_request_id, tenant_id, user_id, agent_id, idempotency_key,
                    request_fingerprint, mutation_type, memory_id, target_version_id,
                    source_event_id, state, trace_id, created_at
                ) VALUES (?, ?, 'foreign-user', 'agent-1', 'foreign-source-scope',
                          decode('dcba', 'hex'), 'INVALIDATE', ?, ?, ?, 'STARTED',
                          'trace-test', clock_timestamp())
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                lineageId,
                versionId,
                fixture.sourceEventId()));

    assertSqlState(
        "55000",
        () ->
            execute(
                """
                UPDATE memos.memory_lineage
                   SET lifecycle_state = 'ERASED', lock_version = lock_version + 1,
                       updated_at = clock_timestamp()
                 WHERE tenant_id = ? AND memory_id = ?
                """,
                fixture.tenantId(),
                lineageId));
  }

  @Test
  void rebuildsCurrentStateFromOrderedTransitionLogIncludingLateBackfill() throws Exception {
    Fixture fixture = fixture("tenant-rebuild");
    UUID lineageId = UUID.randomUUID();
    UUID firstVersion = UUID.randomUUID();
    insertFirstAuthority(fixture, lineageId, firstVersion, UUID.randomUUID());

    UUID backfillVersion = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      UUID backfillCandidate = insertAdditionalCandidate(connection, fixture, 1);
      insertVersion(connection, fixture, lineageId, backfillVersion, 2, backfillCandidate);
      insertSource(connection, fixture, lineageId, backfillVersion, 1, backfillCandidate);
      insertTransition(
          connection,
          fixture,
          lineageId,
          transitionId,
          2,
          "SUPERSEDE",
          new UUID[] {firstVersion, backfillVersion},
          "BACKFILLED_NON_OVERLAPPING",
          backfillCandidate);
      insertStatusChange(
          connection,
          fixture.tenantId(),
          lineageId,
          transitionId,
          0,
          backfillVersion,
          null,
          "HISTORICAL");
      execute(
          connection,
          "SELECT memos.rebuild_memory_current_state(?, ?)",
          fixture.tenantId(),
          lineageId);
      connection.commit();
    }

    execute(
        "UPDATE memos.memory_current_state SET status = 'CONFLICTED' WHERE tenant_id = ? AND memory_id = ?",
        fixture.tenantId(),
        lineageId);
    execute("SELECT memos.rebuild_memory_current_state(?, ?)", fixture.tenantId(), lineageId);

    try (Connection connection = connection();
        var statement =
            connection.prepareStatement(
                """
                SELECT version_id, status FROM memos.memory_current_state
                 WHERE tenant_id = ? AND memory_id = ? ORDER BY version_id
                """)) {
      statement.setString(1, fixture.tenantId());
      statement.setObject(2, lineageId);
      try (var result = statement.executeQuery()) {
        var statuses = new java.util.HashMap<UUID, String>();
        while (result.next()) {
          statuses.put(result.getObject("version_id", UUID.class), result.getString("status"));
        }
        assertThat(statuses)
            .containsEntry(firstVersion, "CURRENT")
            .containsEntry(backfillVersion, "HISTORICAL");
      }
    }
  }

  @Test
  void reinforcementRetainsEveryDistinctSourceWithoutDuplicatingTheAssertionVersion()
      throws Exception {
    Fixture fixture = fixture("tenant-reinforce");
    UUID secondCandidate;
    UUID thirdCandidate;
    try (Connection connection = connection()) {
      secondCandidate = insertAdditionalCandidate(connection, fixture, 1);
      thirdCandidate = insertAdditionalCandidate(connection, fixture, 2);
    }
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    JdbcTemporalMemoryAuthority authority =
        new JdbcTemporalMemoryAuthority(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            new TemporalTransitionPlanner(
                new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator()));
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope(fixture.tenantId(), "user-1", "agent-1"),
            MemoryType.SEMANTIC,
            new CandidateSubject(SubjectKind.USER, "user-1"),
            "residence",
            PredicateCardinality.SINGLE);
    List<UUID> candidates = List.of(fixture.candidateId(), secondCandidate, thirdCandidate);
    for (int index = 0; index < candidates.size(); index++) {
      UUID candidate = candidates.get(index);
      authority.materialize(
          new MaterializeCandidateCommand(
              identity,
              new TextCandidateValue("Hangzhou"),
              "The user lives in Hangzhou.",
              null,
              null,
              0.8,
              0.9,
              new AssertionProvenance(
                  fixture.sourceEventId(),
                  fixture.extractionRunId(),
                  candidate,
                  "fake",
                  "prompt.v1",
                  "model.v1",
                  "write-policy.v1",
                  "memory-candidate.v1",
                  AssertionDerivationRole.EXTRACTED,
                  null),
              new TransitionContext(
                  TransitionActor.WORKER,
                  TransitionSource.CANDIDATE_MATERIALIZATION,
                  "write-policy.v1"),
              java.time.Instant.now(),
              index));
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.memory_version WHERE memory_id = ?",
                Integer.class,
                identity.lineageId().value()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.memory_source WHERE memory_id = ?",
                Integer.class,
                identity.lineageId().value()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM memos.memory_state_transition
                 WHERE memory_id = ? AND operation = 'REINFORCE'
                """,
                Integer.class,
                identity.lineageId().value()))
        .isEqualTo(2);
    assertThat(authority.inspect(identity.scope(), identity.lineageId()).orElseThrow().versions())
        .hasSize(1);
  }

  @Test
  void atomicallyCommitsCandidateJobWithLeaseFenceAndConcurrentReplay() throws Exception {
    Fixture fixture = fixture("tenant-atomic");
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TemporalTransitionPlanner planner =
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator());
    JdbcTemporalMemoryAuthority authority =
        new JdbcTemporalMemoryAuthority(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), planner);
    ClaimedJob job = fixture.claimedJob();
    var candidate = authority.loadCandidates(job).getFirst();
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope(fixture.tenantId(), "user-1", "agent-1"),
            candidate.proposal().memoryType(),
            candidate.proposal().subject(),
            candidate.proposal().predicate(),
            PredicateCardinality.SINGLE);
    MaterializeCandidateCommand materialize =
        new MaterializeCandidateCommand(
            identity,
            candidate.proposal().value(),
            candidate.proposal().normalizedContent(),
            null,
            null,
            candidate.proposal().importance(),
            candidate.proposal().confidence(),
            candidate.provenance(),
            candidate.transitionContext(),
            java.time.Instant.now(),
            0);
    var plan = planner.planMaterialization(MemoryLineageSnapshot.empty(identity), materialize);
    CommitTemporalMaterialization commit =
        new CommitTemporalMaterialization(
            job,
            List.of(new PlannedCandidateMaterialization(candidate, materialize, plan)),
            "projection-policy.v1",
            java.time.Instant.now());

    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return authority.commit(commit);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return authority.commit(commit);
              });
      start.countDown();
      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder(
              TemporalMaterializationCommitResult.COMMITTED,
              TemporalMaterializationCommitResult.ALREADY_COMMITTED);
    }
    assertThat(authority.commit(commit))
        .isEqualTo(TemporalMaterializationCommitResult.ALREADY_COMMITTED);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM memos.outbox_job WHERE job_id = ?",
                String.class,
                fixture.jobId()))
        .isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.materialization_result WHERE job_id = ?",
                Integer.class,
                fixture.jobId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM memos.outbox_job
                 WHERE tenant_id = ? AND job_type = 'PROJECTION_BUILD'
                """,
                Integer.class,
                fixture.tenantId()))
        .isEqualTo(1);

    var persisted = authority.inspect(identity.scope(), identity.lineageId()).orElseThrow();
    InvalidationSelection invalidation =
        new InvalidationSelection(
            identity.scope(),
            identity.lineageId(),
            persisted.versions().getFirst().versionId(),
            fixture.sourceEventId(),
            "invalidate-once",
            persisted.lockVersion(),
            "USER_REQUESTED_REMOVAL",
            "trace-invalidate",
            java.time.Instant.now());
    CountDownLatch mutationStart = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                mutationStart.await();
                return authority.invalidate(invalidation).disposition();
              });
      var second =
          executor.submit(
              () -> {
                mutationStart.await();
                return authority.invalidate(invalidation).disposition();
              });
      mutationStart.countDown();
      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder(
              TemporalMutationDisposition.APPLIED, TemporalMutationDisposition.REPLAYED);
    }
    InvalidationSelection replayWithChangedReason =
        new InvalidationSelection(
            invalidation.scope(),
            invalidation.lineageId(),
            invalidation.versionId(),
            invalidation.sourceEventId(),
            invalidation.idempotencyKey(),
            invalidation.expectedLockVersion(),
            "CHANGED_REASON_CONFLICTS",
            invalidation.traceId(),
            java.time.Instant.now());
    assertThatThrownBy(() -> authority.invalidate(replayWithChangedReason))
        .isInstanceOfSatisfying(
            TemporalMutationException.class,
            exception ->
                assertThat(exception.kind())
                    .isEqualTo(TemporalMutationFailureKind.IDEMPOTENCY_CONFLICT));
    assertThatThrownBy(
            () ->
                authority.invalidate(
                    new InvalidationSelection(
                        new LineageScope("foreign-tenant", "user-1", "agent-1"),
                        invalidation.lineageId(),
                        invalidation.versionId(),
                        invalidation.sourceEventId(),
                        "foreign-scope",
                        invalidation.expectedLockVersion(),
                        "FOREIGN_SCOPE",
                        "trace-foreign",
                        java.time.Instant.now())))
        .isInstanceOfSatisfying(
            TemporalMutationException.class,
            exception ->
                assertThat(exception.kind()).isEqualTo(TemporalMutationFailureKind.NOT_FOUND));

    Fixture stale = fixture("tenant-stale-atomic");
    ClaimedJob staleJob = stale.claimedJob();
    jdbc.update(
        "UPDATE memos.outbox_job SET lease_token = gen_random_uuid() WHERE job_id = ?",
        stale.jobId());
    assertThat(authority.loadCandidates(staleJob)).isEmpty();
    assertThat(
            authority.commit(
                new CommitTemporalMaterialization(
                    staleJob,
                    commit.plannedCandidates(),
                    commit.projectionPolicyVersion(),
                    commit.committedAt())))
        .isEqualTo(TemporalMaterializationCommitResult.LEASE_LOST);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.materialization_result WHERE job_id = ?",
                Integer.class,
                stale.jobId()))
        .isZero();
  }

  @Test
  void correctionAppendsReplacementAndReplaysWithoutMutatingTheOriginal() throws Exception {
    Fixture fixture = fixture("tenant-correction");
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TemporalTransitionPlanner planner =
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator());
    JdbcTemporalMemoryAuthority authority =
        new JdbcTemporalMemoryAuthority(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), planner);
    ClaimedJob job = fixture.claimedJob();
    var candidate = authority.loadCandidates(job).getFirst();
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope(fixture.tenantId(), "user-1", "agent-1"),
            candidate.proposal().memoryType(),
            candidate.proposal().subject(),
            candidate.proposal().predicate(),
            PredicateCardinality.SINGLE);
    MaterializeCandidateCommand materialize =
        new MaterializeCandidateCommand(
            identity,
            candidate.proposal().value(),
            candidate.proposal().normalizedContent(),
            null,
            null,
            candidate.proposal().importance(),
            candidate.proposal().confidence(),
            candidate.provenance(),
            candidate.transitionContext(),
            java.time.Instant.now(),
            0);
    CommitTemporalMaterialization commit =
        new CommitTemporalMaterialization(
            job,
            List.of(
                new PlannedCandidateMaterialization(
                    candidate,
                    materialize,
                    planner.planMaterialization(
                        MemoryLineageSnapshot.empty(identity), materialize))),
            "projection-policy.v1",
            java.time.Instant.now());
    assertThat(authority.commit(commit)).isEqualTo(TemporalMaterializationCommitResult.COMMITTED);

    UUID correctionCandidate;
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      correctionCandidate = insertAdditionalCandidate(connection, fixture, 1);
      insertCandidatePolicyDecision(connection, fixture, correctionCandidate, 1);
      connection.commit();
    }
    MemoryLineageSnapshot before =
        authority.inspect(identity.scope(), identity.lineageId()).orElseThrow();
    CorrectionSelection correction =
        new CorrectionSelection(
            identity.scope(),
            identity.lineageId(),
            before.versions().getFirst().versionId(),
            fixture.sourceEventId(),
            correctionCandidate,
            "correct-once",
            before.lockVersion(),
            "USER_CORRECTION",
            "trace-correction",
            java.time.Instant.now());

    assertThat(authority.correct(correction).disposition())
        .isEqualTo(TemporalMutationDisposition.APPLIED);
    assertThat(authority.correct(correction).disposition())
        .isEqualTo(TemporalMutationDisposition.REPLAYED);
    MemoryLineageSnapshot after =
        authority.inspect(identity.scope(), identity.lineageId()).orElseThrow();
    assertThat(after.versions()).hasSize(2);
    assertThat(after.transitions())
        .extracting(transition -> transition.operation().name())
        .containsExactly("CREATE", "INVALIDATE");
    assertThat(after.statuses().get(before.versions().getFirst().versionId()).name())
        .isEqualTo("INVALIDATED");
    assertThat(
            after.versions().stream()
                .filter(
                    version -> after.statuses().get(version.versionId()).name().equals("CURRENT")))
        .singleElement()
        .satisfies(
            version ->
                assertThat(version.normalizedContent()).isEqualTo("The user lived in Ningbo."));

    UUID assistantSource = insertAssistantMutationSource(fixture.tenantId());
    var currentVersion =
        after.versions().stream()
            .filter(version -> after.statuses().get(version.versionId()).name().equals("CURRENT"))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                authority.invalidate(
                    new InvalidationSelection(
                        identity.scope(),
                        identity.lineageId(),
                        currentVersion.versionId(),
                        assistantSource,
                        "assistant-cannot-invalidate",
                        after.lockVersion(),
                        "UNTRUSTED_SOURCE",
                        "trace-untrusted",
                        java.time.Instant.now())))
        .isInstanceOfSatisfying(
            TemporalMutationException.class,
            exception ->
                assertThat(exception.kind()).isEqualTo(TemporalMutationFailureKind.NOT_FOUND));
  }

  @Test
  void commitsMultipleCandidatesForOneNaturalLineageInSequence() throws Exception {
    Fixture fixture = fixture("tenant-same-lineage-batch");
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      UUID secondCandidate = insertAdditionalCandidate(connection, fixture, 1);
      insertCandidatePolicyDecision(connection, fixture, secondCandidate, 1);
      execute(
          connection,
          """
          INSERT INTO memos.candidate_policy_decision (
              decision_id, tenant_id, run_id, candidate_id, ordinal, decision,
              sensitivity_action, effective_scope, reason_codes, policy_version, decided_at
          ) VALUES (?, ?, ?, ?, 0, 'IGNORE', 'NONE', 'USER',
                    ARRAY['LEGACY_POLICY'], 'legacy-policy.v0', clock_timestamp())
          """,
          UUID.randomUUID(),
          fixture.tenantId(),
          fixture.extractionRunId(),
          fixture.candidateId());
      connection.commit();
    }
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TemporalTransitionPlanner planner =
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator());
    JdbcTemporalMemoryAuthority authority =
        new JdbcTemporalMemoryAuthority(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), planner);
    ClaimedJob job = fixture.claimedJob();
    var candidates = authority.loadCandidates(job);
    assertThat(candidates).hasSize(2);
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope(fixture.tenantId(), "user-1", "agent-1"),
            MemoryType.SEMANTIC,
            candidates.getFirst().proposal().subject(),
            "residence",
            PredicateCardinality.SINGLE);
    MemoryLineageSnapshot snapshot = MemoryLineageSnapshot.empty(identity);
    List<PlannedCandidateMaterialization> planned = new ArrayList<>();
    for (var candidate : candidates) {
      MaterializeCandidateCommand command =
          new MaterializeCandidateCommand(
              snapshot.identity(),
              candidate.proposal().value(),
              candidate.proposal().normalizedContent(),
              null,
              null,
              candidate.proposal().importance(),
              candidate.proposal().confidence(),
              candidate.provenance(),
              candidate.transitionContext(),
              java.time.Instant.now(),
              snapshot.lockVersion());
      var plan = planner.planMaterialization(snapshot, command);
      planned.add(new PlannedCandidateMaterialization(candidate, command, plan));
      snapshot = plan.resultingSnapshot();
    }

    assertThat(
            authority.commit(
                new CommitTemporalMaterialization(
                    job, planned, "projection-policy.v1", java.time.Instant.now())))
        .isEqualTo(TemporalMaterializationCommitResult.COMMITTED);
    MemoryLineageSnapshot persisted =
        authority.inspect(identity.scope(), identity.lineageId()).orElseThrow();
    assertThat(persisted.lockVersion()).isEqualTo(2);
    assertThat(persisted.versions()).hasSize(2);
    assertThat(persisted.statuses().values())
        .allMatch(status -> status.name().equals("CONFLICTED"));
  }

  @Test
  void bindsTemporalCommitToTheClaimedJobCandidateAndReplansInsideTheTransaction()
      throws Exception {
    Fixture fixture = fixture("tenant-commit-binding");
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    TemporalTransitionPlanner planner =
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator());
    JdbcTemporalMemoryAuthority authority =
        new JdbcTemporalMemoryAuthority(
            new JdbcTemplate(dataSource),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            planner);
    ClaimedJob job = fixture.claimedJob();
    var candidate = authority.loadCandidates(job).getFirst();
    MemoryLineageIdentity forged =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope("foreign-tenant", "user-1", "agent-1"),
            candidate.proposal().memoryType(),
            candidate.proposal().subject(),
            candidate.proposal().predicate(),
            PredicateCardinality.SINGLE);
    MaterializeCandidateCommand command =
        new MaterializeCandidateCommand(
            forged,
            candidate.proposal().value(),
            candidate.proposal().normalizedContent(),
            null,
            null,
            candidate.proposal().importance(),
            candidate.proposal().confidence(),
            candidate.provenance(),
            candidate.transitionContext(),
            java.time.Instant.now(),
            0);
    var plan =
        new TemporalTransitionPlanner(
                new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator())
            .planMaterialization(MemoryLineageSnapshot.empty(forged), command);

    assertThatThrownBy(
            () ->
                authority.commit(
                    new CommitTemporalMaterialization(
                        job,
                        List.of(new PlannedCandidateMaterialization(candidate, command, plan)),
                        "projection-policy.v1",
                        java.time.Instant.now())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("temporal commit binding mismatch");
    assertThat(
            new JdbcTemplate(dataSource)
                .queryForObject(
                    "SELECT count(*) FROM memos.memory_lineage WHERE tenant_id = 'foreign-tenant'",
                    Integer.class))
        .isZero();

    MemoryLineageIdentity bound =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope(fixture.tenantId(), "user-1", "agent-1"),
            candidate.proposal().memoryType(),
            candidate.proposal().subject(),
            candidate.proposal().predicate(),
            PredicateCardinality.SINGLE);
    MaterializeCandidateCommand boundCommand =
        new MaterializeCandidateCommand(
            bound,
            candidate.proposal().value(),
            candidate.proposal().normalizedContent(),
            null,
            null,
            candidate.proposal().importance(),
            candidate.proposal().confidence(),
            candidate.provenance(),
            candidate.transitionContext(),
            java.time.Instant.now(),
            0);
    MaterializeCandidateCommand forgedCommand =
        new MaterializeCandidateCommand(
            bound,
            new TextCandidateValue("forged"),
            "forged content",
            null,
            null,
            candidate.proposal().importance(),
            candidate.proposal().confidence(),
            candidate.provenance(),
            candidate.transitionContext(),
            boundCommand.decidedAt(),
            0);

    assertThat(
            authority.commit(
                new CommitTemporalMaterialization(
                    job,
                    List.of(
                        new PlannedCandidateMaterialization(
                            candidate,
                            boundCommand,
                            planner.planMaterialization(
                                MemoryLineageSnapshot.empty(bound), forgedCommand))),
                    "projection-policy.v1",
                    java.time.Instant.now())))
        .isEqualTo(TemporalMaterializationCommitResult.COMMITTED);
    MemoryLineageSnapshot persisted =
        authority.inspect(bound.scope(), bound.lineageId()).orElseThrow();
    assertThat(persisted.versions())
        .singleElement()
        .satisfies(
            version -> {
              assertThat(version.value()).isEqualTo(candidate.proposal().value());
              assertThat(version.normalizedContent())
                  .isEqualTo(candidate.proposal().normalizedContent());
            });
  }

  @Test
  void projectionIntentFailureRollsBackEveryAuthoritativeEffect() throws Exception {
    Fixture fixture = fixture("tenant-projection-rollback");
    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TemporalTransitionPlanner planner =
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new RandomTemporalIdentityGenerator());
    JdbcTemporalMemoryAuthority authority =
        new JdbcTemporalMemoryAuthority(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), planner);
    ClaimedJob job = fixture.claimedJob();
    var candidate = authority.loadCandidates(job).getFirst();
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(UUID.randomUUID()),
            new LineageScope(fixture.tenantId(), "user-1", "agent-1"),
            candidate.proposal().memoryType(),
            candidate.proposal().subject(),
            candidate.proposal().predicate(),
            PredicateCardinality.SINGLE);
    MaterializeCandidateCommand materialize =
        new MaterializeCandidateCommand(
            identity,
            candidate.proposal().value(),
            candidate.proposal().normalizedContent(),
            null,
            null,
            candidate.proposal().importance(),
            candidate.proposal().confidence(),
            candidate.provenance(),
            candidate.transitionContext(),
            java.time.Instant.now(),
            0);
    CommitTemporalMaterialization commit =
        new CommitTemporalMaterialization(
            job,
            List.of(
                new PlannedCandidateMaterialization(
                    candidate,
                    materialize,
                    planner.planMaterialization(
                        MemoryLineageSnapshot.empty(identity), materialize))),
            "projection-policy.v1",
            java.time.Instant.now());

    execute(
        """
        CREATE FUNCTION memos.reject_feature3_projection()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'injected projection failure'; END
        $$
        """);
    execute(
        """
        CREATE TRIGGER reject_feature3_projection
        BEFORE INSERT ON memos.outbox_job
        FOR EACH ROW WHEN (NEW.job_type = 'PROJECTION_BUILD')
        EXECUTE FUNCTION memos.reject_feature3_projection()
        """);
    try {
      assertThatThrownBy(() -> authority.commit(commit)).isInstanceOf(RuntimeException.class);
    } finally {
      execute("DROP TRIGGER reject_feature3_projection ON memos.outbox_job");
      execute("DROP FUNCTION memos.reject_feature3_projection()");
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.memory_lineage WHERE tenant_id = ?",
                Integer.class,
                fixture.tenantId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM memos.outbox_job WHERE job_id = ?",
                String.class,
                fixture.jobId()))
        .isEqualTo("CLAIMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.materialization_result WHERE job_id = ?",
                Integer.class,
                fixture.jobId()))
        .isZero();
  }

  @Test
  void projectionCommitFencesStaleWorkAndRetrievalHardFiltersScopeAndTruth() throws Exception {
    Fixture fixture = fixture("tenant-feature4-projection");
    UUID lineageId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID firstTransitionId = UUID.randomUUID();
    insertFirstAuthority(fixture, lineageId, versionId, firstTransitionId);

    DataSource dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    JdbcProjectionBuildStore store =
        new JdbcProjectionBuildStore(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

    ClaimedJob staleJob = insertClaimedProjectionJob(fixture, firstTransitionId);
    ProjectionBuildPlan stalePlan = store.load(staleJob).orElseThrow();
    assertThat(stalePlan.transitionSequence()).isEqualTo(1);

    UUID secondTransitionId = UUID.randomUUID();
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      UUID reinforcement = insertAdditionalCandidate(connection, fixture, 1);
      insertTransition(
          connection,
          fixture,
          lineageId,
          secondTransitionId,
          2,
          "REINFORCE",
          new UUID[] {versionId},
          "DUPLICATE_REINFORCEMENT",
          reinforcement);
      connection.commit();
    }

    assertThat(
            store.commit(
                new CommitProjectionBuild(
                    stalePlan, projected(stalePlan), java.time.Instant.now())))
        .isEqualTo(ProjectionCommitResult.SUPERSEDED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = ?",
                Integer.class,
                fixture.tenantId()))
        .isZero();

    ClaimedJob currentJob = insertClaimedProjectionJob(fixture, secondTransitionId);
    ProjectionBuildPlan currentPlan = store.load(currentJob).orElseThrow();
    ClaimedJob forgedJob =
        new ClaimedJob(
            currentJob.jobId(),
            currentJob.jobType(),
            currentJob.scope(),
            currentJob.sourceEventId(),
            currentJob.semanticJobKey(),
            currentJob.policyVersion(),
            currentJob.modelVersion(),
            currentJob.attempt(),
            currentJob.maxAttempts(),
            currentJob.leaseOwner(),
            new LeaseToken(UUID.randomUUID()),
            currentJob.leaseExpiresAt(),
            currentJob.traceId());
    ProjectionBuildPlan forgedPlan =
        new ProjectionBuildPlan(
            forgedJob,
            currentPlan.memoryId(),
            currentPlan.transitionId(),
            currentPlan.transitionSequence(),
            currentPlan.items());
    assertThat(
            store.commit(
                new CommitProjectionBuild(
                    forgedPlan, projected(forgedPlan), java.time.Instant.now())))
        .isEqualTo(ProjectionCommitResult.LEASE_LOST);

    assertThat(
            store.commit(
                new CommitProjectionBuild(
                    currentPlan, projected(currentPlan), java.time.Instant.now())))
        .isEqualTo(ProjectionCommitResult.COMMITTED);
    assertThat(
            jdbc.queryForObject(
                "SELECT transition_sequence FROM memos.memory_projection_checkpoint WHERE tenant_id = ? AND memory_id = ?",
                Long.class,
                fixture.tenantId(),
                lineageId))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(input_tokens) FROM memos.projection_provider_usage WHERE tenant_id = ?",
                Long.class,
                fixture.tenantId()))
        .isEqualTo(8L);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(model_calls) FROM memos.projection_provider_usage WHERE tenant_id = ?",
                Long.class,
                fixture.tenantId()))
        .isEqualTo(2L);

    JdbcRetrievalCandidateStore candidates = new JdbcRetrievalCandidateStore(jdbc);
    CandidateStoreQuery scopedQuery = candidateQuery(fixture.tenantId(), "user-1", "agent-1");
    assertThat(candidates.findCandidates(scopedQuery))
        .isNotEmpty()
        .allSatisfy(candidate -> assertThat(candidate.memory().memoryId()).isEqualTo(lineageId));
    assertThat(candidates.findCandidates(candidateQuery(fixture.tenantId(), "other", "agent-1")))
        .isEmpty();
    assertThat(candidates.findCandidates(candidateQuery(fixture.tenantId(), "user-1", "other")))
        .isEmpty();
    assertThat(candidates.findCandidates(candidateQuery("other-tenant", "user-1", "agent-1")))
        .isEmpty();

    jdbc.update(
        "UPDATE memos.memory_search_projection SET truth_status = 'HISTORICAL' WHERE tenant_id = ? AND memory_id = ?",
        fixture.tenantId(),
        lineageId);
    assertThat(candidates.findCandidates(scopedQuery)).isEmpty();
  }

  private static void insertFirstAuthority(
      Fixture fixture, UUID lineageId, UUID versionId, UUID transitionId) throws SQLException {
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      execute(
          connection,
          """
          INSERT INTO memos.memory_lineage (
              memory_id, tenant_id, user_id, agent_id, memory_type, subject_kind,
              subject_label, predicate, predicate_cardinality, lifecycle_state,
              lock_version, created_at, updated_at
          ) VALUES (?, ?, 'user-1', 'agent-1', 'SEMANTIC', 'USER', 'user-1',
                    'residence', 'SINGLE', 'ACTIVE', 1, clock_timestamp(), clock_timestamp())
          """,
          lineageId,
          fixture.tenantId());
      insertVersion(connection, fixture, lineageId, versionId, 1);
      insertSource(connection, fixture, lineageId, versionId, 0);
      insertTransition(
          connection,
          fixture,
          lineageId,
          transitionId,
          1,
          "CREATE",
          new UUID[] {versionId},
          "FIRST_ASSERTION");
      insertStatusChange(
          connection, fixture.tenantId(), lineageId, transitionId, 0, versionId, null, "CURRENT");
      execute(
          connection,
          "SELECT memos.rebuild_memory_current_state(?, ?)",
          fixture.tenantId(),
          lineageId);
      connection.commit();
    }
  }

  private static ClaimedJob insertClaimedProjectionJob(Fixture fixture, UUID transitionId)
      throws SQLException {
    UUID jobId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    String semanticKey = "projection/" + transitionId;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    execute(
        """
        INSERT INTO memos.outbox_job (
            job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
            semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
            replay_count, next_attempt_at, payload_reference, lease_owner, lease_token,
            lease_expires_at, trace_id, created_at, updated_at
        ) VALUES (?, ?, ?, 'PROJECTION_BUILD', 'MEMORY_TRANSITION', ?, ?,
                  'projection-v1', 'deterministic-hashing-1024-v1', 'CLAIMED', 1, 3, 0,
                  NULL, ?, 'projection-worker', ?, ?, 'trace-projection', ?, ?)
        """,
        jobId,
        fixture.tenantId(),
        fixture.sourceEventId(),
        transitionId,
        semanticKey,
        fixture.sourceEventId(),
        leaseToken,
        now.plusMinutes(5),
        now,
        now);
    return new ClaimedJob(
        new JobId(jobId),
        JobType.PROJECTION_BUILD,
        new MemoryScope(fixture.tenantId(), "user-1", "agent-1"),
        fixture.sourceEventId(),
        new SemanticJobKey(semanticKey),
        "projection-v1",
        "deterministic-hashing-1024-v1",
        1,
        3,
        new WorkerId("projection-worker"),
        new LeaseToken(leaseToken),
        now.plusMinutes(5).toInstant(),
        "trace-projection");
  }

  private static List<ProjectedVersionBuild> projected(ProjectionBuildPlan plan) {
    List<Float> vector = unitVector();
    return plan.items().stream()
        .map(
            item ->
                new ProjectedVersionBuild(
                    item,
                    new ProjectionEmbedding(
                        vector, "deterministic", "deterministic-hashing-1024-v1", 4)))
        .toList();
  }

  private static CandidateStoreQuery candidateQuery(
      String tenantId, String userId, String agentId) {
    return new CandidateStoreQuery(
        new LineageScope(tenantId, userId, agentId),
        "user lives in Hangzhou",
        new QueryIntent(TemporalQueryIntent.PRESENT, null),
        "residence",
        "user-1",
        20,
        Set.of(CandidateSource.VECTOR, CandidateSource.LEXICAL, CandidateSource.STRUCTURED),
        new EmbeddingResult(unitVector(), "deterministic", "deterministic-hashing-1024-v1", 4));
  }

  private static List<Float> unitVector() {
    List<Float> vector = new ArrayList<>(Collections.nCopies(1_024, 0.0f));
    vector.set(0, 1.0f);
    return List.copyOf(vector);
  }

  private static void insertVersion(
      Connection connection, Fixture fixture, UUID lineageId, UUID versionId, long ordinal)
      throws SQLException {
    insertVersion(connection, fixture, lineageId, versionId, ordinal, fixture.candidateId());
  }

  private static void insertVersion(
      Connection connection,
      Fixture fixture,
      UUID lineageId,
      UUID versionId,
      long ordinal,
      UUID candidateId)
      throws SQLException {
    execute(
        connection,
        """
        INSERT INTO memos.memory_version (
            version_id, tenant_id, memory_id, version_number, candidate_id, content_state,
            value_json, normalized_content, importance, confidence,
            event_time_original, event_time_start, event_time_end, event_time_precision,
            event_time_confidence, valid_time_original, valid_time_start, valid_time_end,
            valid_time_precision, valid_time_confidence, content_fingerprint,
            extractor_version, prompt_version, policy_version, model_version, schema_version,
            transaction_time
        ) VALUES (?, ?, ?, ?, ?, 'AVAILABLE', '"Hangzhou"'::jsonb,
                  'The user lives in Hangzhou.', 0.8, 0.9,
                  'May 2026', '2026-05-01T00:00:00Z', '2026-06-01T00:00:00Z',
                  'MONTH', 0.9, 'from May 2026', '2026-05-01T00:00:00Z', NULL,
                  'MONTH', 0.9, decode('cafe', 'hex'), 'extractor.v1', 'prompt.v1',
                  'write-policy.v1', 'model.v1', 'memory-candidate.v1', clock_timestamp())
        """,
        versionId,
        fixture.tenantId(),
        lineageId,
        ordinal,
        candidateId);
  }

  private static void insertSource(
      Connection connection, Fixture fixture, UUID lineageId, UUID versionId, int ordinal)
      throws SQLException {
    insertSource(connection, fixture, lineageId, versionId, ordinal, fixture.candidateId());
  }

  private static void insertSource(
      Connection connection,
      Fixture fixture,
      UUID lineageId,
      UUID versionId,
      int ordinal,
      UUID candidateId)
      throws SQLException {
    execute(
        connection,
        """
        INSERT INTO memos.memory_source (
            tenant_id, memory_id, version_id, source_event_id, extraction_run_id,
            candidate_id, policy_version, derivation_role, evidence_ordinal,
            evidence_start, evidence_end, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'write-policy.v1', 'EXTRACTED', ?, 0, 12,
                  clock_timestamp())
        """,
        fixture.tenantId(),
        lineageId,
        versionId,
        fixture.sourceEventId(),
        fixture.extractionRunId(),
        candidateId,
        ordinal);
  }

  private static void insertTransition(
      Connection connection,
      Fixture fixture,
      UUID lineageId,
      UUID transitionId,
      long sequence,
      String operation,
      UUID[] relatedVersions,
      String reason)
      throws SQLException {
    insertTransition(
        connection,
        fixture,
        lineageId,
        transitionId,
        sequence,
        operation,
        relatedVersions,
        reason,
        fixture.candidateId());
  }

  private static void insertTransition(
      Connection connection,
      Fixture fixture,
      UUID lineageId,
      UUID transitionId,
      long sequence,
      String operation,
      UUID[] relatedVersions,
      String reason,
      UUID candidateId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO memos.memory_state_transition (
                transition_id, tenant_id, memory_id, transition_sequence, operation,
                caused_by_candidate_id, related_version_ids, reason, actor_type, actor_id,
                transition_source, policy_version, transaction_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WORKER', 'worker-test',
                      'CANDIDATE_MATERIALIZATION', 'write-policy.v1', clock_timestamp())
            """)) {
      statement.setObject(1, transitionId);
      statement.setString(2, fixture.tenantId());
      statement.setObject(3, lineageId);
      statement.setLong(4, sequence);
      statement.setString(5, operation);
      statement.setObject(6, candidateId);
      statement.setArray(7, connection.createArrayOf("uuid", relatedVersions));
      statement.setString(8, reason);
      statement.executeUpdate();
    }
  }

  private static UUID insertAdditionalCandidate(Connection connection, Fixture fixture, int ordinal)
      throws SQLException {
    UUID candidateId = UUID.randomUUID();
    execute(
        connection,
        """
        INSERT INTO memos.memory_candidate (
            candidate_id, tenant_id, run_id, source_event_id, ordinal, schema_version,
            proposed_decision, subject_kind, subject_label, predicate, value_json,
            normalized_content, memory_type, importance, confidence, source_type,
            source_trust, sensitivity, relation_hints_json, content_fingerprint,
            content_state, created_at
        ) VALUES (?, ?, ?, ?, ?, 'memory-candidate.v1', 'REMEMBER', 'USER', 'user-1',
                  'residence', '"Ningbo"'::jsonb, 'The user lived in Ningbo.',
                  'SEMANTIC', 0.8, 0.9, 'CONVERSATION_MESSAGE', 'DIRECT_USER',
                  ARRAY['LOCATION'], '[]'::jsonb, decode('face', 'hex'), 'AVAILABLE',
                  clock_timestamp())
        """,
        candidateId,
        fixture.tenantId(),
        fixture.extractionRunId(),
        fixture.sourceEventId(),
        ordinal);
    return candidateId;
  }

  private static void insertCandidatePolicyDecision(
      Connection connection, Fixture fixture, UUID candidateId, int ordinal) throws SQLException {
    execute(
        connection,
        """
        INSERT INTO memos.candidate_policy_decision (
            decision_id, tenant_id, run_id, candidate_id, ordinal, decision,
            sensitivity_action, effective_scope, reason_codes, policy_version, decided_at
        ) VALUES (?, ?, ?, ?, ?, 'REMEMBER', 'NONE', 'USER',
                  ARRAY['POLICY_ACCEPTED'], 'write-policy.v1', clock_timestamp())
        """,
        UUID.randomUUID(),
        fixture.tenantId(),
        fixture.extractionRunId(),
        candidateId,
        ordinal);
  }

  private static UUID insertAssistantMutationSource(String tenantId) throws SQLException {
    UUID sourceEventId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    execute(
        """
        INSERT INTO memos.source_event (
            source_event_id, tenant_id, user_id, agent_id, source_id, session_id,
            idempotency_key, actor_type, source_type, trust_level, occurred_at,
            received_at, payload, request_fingerprint, deletion_state, trace_id, created_at
        ) VALUES (?, ?, 'user-1', 'agent-1', ?, 'session-1', ?, 'ASSISTANT',
                  'DIRECT_MEMORY_COMMAND', 'ASSISTANT_GENERATED', ?, ?,
                  '{"content":"untrusted mutation"}'::jsonb, decode('aabb', 'hex'),
                  'ACTIVE', 'trace-untrusted', ?)
        """,
        sourceEventId,
        tenantId,
        "source-" + sourceEventId,
        "idempotency-" + sourceEventId,
        now.minusSeconds(1),
        now,
        now);
    return sourceEventId;
  }

  private static void insertStatusChange(
      Connection connection,
      String tenantId,
      UUID lineageId,
      UUID transitionId,
      int ordinal,
      UUID versionId,
      String fromStatus,
      String toStatus)
      throws SQLException {
    execute(
        connection,
        """
        INSERT INTO memos.memory_status_change (
            tenant_id, transition_id, change_ordinal, memory_id, version_id,
            from_status, to_status
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        transitionId,
        ordinal,
        lineageId,
        versionId,
        fromStatus,
        toStatus);
  }

  private static Fixture fixture(String tenantPrefix) throws SQLException {
    String tenantId = tenantPrefix + "-" + UUID.randomUUID();
    UUID sourceEventId = UUID.randomUUID();
    UUID sourceJobId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID extractionRunId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    execute(
        """
        INSERT INTO memos.source_event (
            source_event_id, tenant_id, user_id, agent_id, source_id, session_id,
            idempotency_key, actor_type, source_type, trust_level, occurred_at,
            received_at, payload, request_fingerprint, deletion_state, trace_id, created_at
        ) VALUES (?, ?, 'user-1', 'agent-1', ?, 'session-1', ?, 'USER',
                  'DIRECT_MEMORY_COMMAND', 'DIRECT_USER', ?, ?, '{"content":"moved"}'::jsonb,
                  decode('beef', 'hex'), 'ACTIVE', 'trace-test', ?)
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
            lease_expires_at, trace_id, created_at, updated_at
        ) VALUES (?, ?, ?, 'CANDIDATE_MATERIALIZATION', 'EXTRACTION_RUN', ?, ?,
                  'write-policy.v1', 'model.v1', 'CLAIMED', 1, 3, 0, NULL, ?, 'worker-test',
                  ?, ?, 'trace-test', ?, ?)
        """,
        sourceJobId,
        tenantId,
        sourceEventId,
        extractionRunId,
        "candidate-materialization/" + extractionRunId,
        sourceEventId,
        leaseToken,
        now.plusMinutes(5),
        now,
        now);
    execute(
        """
        INSERT INTO memos.extraction_attempt (
            attempt_id, tenant_id, job_id, job_attempt, lease_token, provider,
            model_version, prompt_version, schema_version, policy_version, state,
            started_at, finished_at, input_tokens, output_tokens, model_calls,
            duration_ms, finish_reason
        ) VALUES (?, ?, ?, 1, ?, 'fake', 'model.v1', 'prompt.v1', 'memory-candidate.v1',
                  'write-policy.v1', 'SUCCEEDED', ?, ?, 1, 1, 1, 1, 'VALID_SCHEMA')
        """,
        attemptId,
        tenantId,
        sourceJobId,
        leaseToken,
        now.minusSeconds(1),
        now);
    execute(
        """
        INSERT INTO memos.extraction_run (
            run_id, tenant_id, source_event_id, extraction_job_id, semantic_run_key,
            attempt_id, provider, model_version, prompt_version, schema_version,
            policy_version, candidate_count, remember_count, ignore_count, review_count,
            created_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'fake', 'model.v1', 'prompt.v1',
                  'memory-candidate.v1', 'write-policy.v1', 1, 1, 0, 0, ?)
        """,
        extractionRunId,
        tenantId,
        sourceEventId,
        sourceJobId,
        "extract/" + extractionRunId,
        attemptId,
        now);
    execute(
        """
        INSERT INTO memos.memory_candidate (
            candidate_id, tenant_id, run_id, source_event_id, ordinal, schema_version,
            proposed_decision, subject_kind, subject_label, predicate, value_json,
            normalized_content, memory_type, importance, confidence, source_type,
            source_trust, sensitivity, relation_hints_json, content_fingerprint,
            content_state, created_at
        ) VALUES (?, ?, ?, ?, 0, 'memory-candidate.v1', 'REMEMBER', 'USER', 'user-1',
                  'residence', '"Hangzhou"'::jsonb, 'The user lives in Hangzhou.',
                  'SEMANTIC', 0.8, 0.9, 'CONVERSATION_MESSAGE', 'DIRECT_USER',
                  ARRAY['LOCATION'], '[]'::jsonb, decode('cafe', 'hex'), 'AVAILABLE', ?)
        """,
        candidateId,
        tenantId,
        extractionRunId,
        sourceEventId,
        now);
    execute(
        """
        INSERT INTO memos.candidate_policy_decision (
            decision_id, tenant_id, run_id, candidate_id, ordinal, decision,
            sensitivity_action, effective_scope, reason_codes, policy_version, decided_at
        ) VALUES (?, ?, ?, ?, 0, 'REMEMBER', 'NONE', 'USER',
                  ARRAY['POLICY_ACCEPTED'], 'write-policy.v1', ?)
        """,
        UUID.randomUUID(),
        tenantId,
        extractionRunId,
        candidateId,
        now);
    return new Fixture(
        tenantId,
        sourceEventId,
        extractionRunId,
        candidateId,
        sourceJobId,
        leaseToken,
        "candidate-materialization/" + extractionRunId,
        now.plusMinutes(5).toInstant());
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  private static void execute(String sql, Object... arguments) throws SQLException {
    try (Connection connection = connection()) {
      execute(connection, sql, arguments);
    }
  }

  private static void execute(Connection connection, String sql, Object... arguments)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      statement.execute();
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
      UUID sourceEventId,
      UUID extractionRunId,
      UUID candidateId,
      UUID jobId,
      UUID leaseToken,
      String semanticJobKey,
      java.time.Instant leaseExpiresAt) {
    private ClaimedJob claimedJob() {
      return new ClaimedJob(
          new JobId(jobId),
          JobType.CANDIDATE_MATERIALIZATION,
          new MemoryScope(tenantId, "user-1", "agent-1"),
          sourceEventId,
          new SemanticJobKey(semanticJobKey),
          "write-policy.v1",
          "model.v1",
          1,
          3,
          new WorkerId("worker-test"),
          new LeaseToken(leaseToken),
          leaseExpiresAt,
          "trace-test");
    }
  }

  @FunctionalInterface
  private interface SqlOperation {
    void run() throws SQLException;
  }
}
