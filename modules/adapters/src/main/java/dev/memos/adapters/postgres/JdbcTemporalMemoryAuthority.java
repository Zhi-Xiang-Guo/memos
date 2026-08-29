package dev.memos.adapters.postgres;

import dev.memos.domain.candidate.BooleanCandidateValue;
import dev.memos.domain.candidate.CandidateRelation;
import dev.memos.domain.candidate.CandidateRelationType;
import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.CandidateValue;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.NumberCandidateValue;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.ProposedTimeRange;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TemporalPrecision;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.domain.temporal.AssertionDerivationRole;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.AssertionStateTransition;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.AssertionVersion;
import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.CorrectAssertionCommand;
import dev.memos.domain.temporal.EvidenceSpan;
import dev.memos.domain.temporal.InvalidTransitionException;
import dev.memos.domain.temporal.InvalidateAssertionCommand;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MaterializeCandidateCommand;
import dev.memos.domain.temporal.MemoryAsOfQuery;
import dev.memos.domain.temporal.MemoryAsOfView;
import dev.memos.domain.temporal.MemoryDiff;
import dev.memos.domain.temporal.MemoryDiffQuery;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineagePage;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.MemoryLineageSummary;
import dev.memos.domain.temporal.MemoryListQuery;
import dev.memos.domain.temporal.OptimisticLockException;
import dev.memos.domain.temporal.PredicateCardinality;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.StatusChange;
import dev.memos.domain.temporal.TemporalMemoryAuthority;
import dev.memos.domain.temporal.TemporalTransitionPlanner;
import dev.memos.domain.temporal.TemporalValidity;
import dev.memos.domain.temporal.TransitionActor;
import dev.memos.domain.temporal.TransitionContext;
import dev.memos.domain.temporal.TransitionPlan;
import dev.memos.domain.temporal.TransitionSource;
import dev.memos.materialization.CandidateForTemporalMaterialization;
import dev.memos.materialization.CandidateId;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.CommitTemporalMaterialization;
import dev.memos.materialization.CorrectionSelection;
import dev.memos.materialization.InvalidationSelection;
import dev.memos.materialization.JobType;
import dev.memos.materialization.PlannedCandidateMaterialization;
import dev.memos.materialization.TemporalCandidateMaterializationStore;
import dev.memos.materialization.TemporalMaterializationCommitResult;
import dev.memos.materialization.TemporalMemoryMutation;
import dev.memos.materialization.TemporalMutationDisposition;
import dev.memos.materialization.TemporalMutationException;
import dev.memos.materialization.TemporalMutationFailureKind;
import dev.memos.materialization.TemporalMutationResult;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL-backed append-only temporal authority and scoped inspection adapter. */
public final class JdbcTemporalMemoryAuthority
    implements TemporalMemoryAuthority,
        TemporalCandidateMaterializationStore,
        TemporalMemoryMutation {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final TemporalTransitionPlanner planner;
  private final String defaultProjectionPolicyVersion;
  private final String projectionModelVersion;

  public JdbcTemporalMemoryAuthority(
      JdbcTemplate jdbc, TransactionTemplate transactions, TemporalTransitionPlanner planner) {
    this(jdbc, transactions, planner, "projection-v1", "deterministic-hashing-1024-v1");
  }

  public JdbcTemporalMemoryAuthority(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      TemporalTransitionPlanner planner,
      String defaultProjectionPolicyVersion,
      String projectionModelVersion) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.defaultProjectionPolicyVersion =
        requiredText(defaultProjectionPolicyVersion, "defaultProjectionPolicyVersion");
    this.projectionModelVersion = requiredText(projectionModelVersion, "projectionModelVersion");
  }

  @Override
  public TransitionPlan materialize(MaterializeCandidateCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(status -> materializeInTransaction(command));
  }

  private TransitionPlan materializeInTransaction(MaterializeCandidateCommand command) {
    ensureLineage(command.lineage(), command.expectedLockVersion());
    MemoryLineageSnapshot snapshot =
        lockAndLoad(command.lineage().scope(), command.lineage().lineageId());
    TransitionPlan plan = planner.planMaterialization(snapshot, command);
    persistPlan(plan, command.lineage(), command.expectedLockVersion(), command.provenance(), null);
    return plan;
  }

  @Override
  public TransitionPlan correct(CorrectAssertionCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(
        status -> {
          MemoryLineageSnapshot snapshot =
              lockAndLoad(command.lineage().scope(), command.lineage().lineageId());
          TransitionPlan plan = planner.planCorrection(snapshot, command);
          persistPlan(
              plan, command.lineage(), command.expectedLockVersion(), command.provenance(), null);
          return plan;
        });
  }

  @Override
  public TransitionPlan invalidate(InvalidateAssertionCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return transactions.execute(
        status -> {
          MemoryLineageSnapshot snapshot = lockAndLoad(command.scope(), command.lineageId());
          TransitionPlan plan = planner.planInvalidation(snapshot, command);
          persistPlan(plan, snapshot.identity(), command.expectedLockVersion(), null, null);
          return plan;
        });
  }

  @Override
  public MemoryLineagePage list(MemoryListQuery query) {
    Objects.requireNonNull(query, "query must not be null");
    UUID cursor = query.cursor() == null ? null : UUID.fromString(query.cursor());
    List<MemoryLineageSummary> rows =
        jdbc.query(
            """
            SELECT lineage.*,
                   max(transition.transaction_time) AS last_transition_at,
                   count(DISTINCT current.version_id)
                       FILTER (WHERE current.status = 'CURRENT') AS current_count,
                   count(DISTINCT current.version_id)
                       FILTER (WHERE current.status = 'HISTORICAL') AS historical_count,
                   count(DISTINCT current.version_id)
                       FILTER (WHERE current.status = 'CONFLICTED') AS conflicted_count,
                   count(DISTINCT current.version_id)
                       FILTER (WHERE current.status = 'INVALIDATED') AS invalidated_count
              FROM memos.memory_lineage lineage
              LEFT JOIN memos.memory_current_state current
                ON current.tenant_id = lineage.tenant_id
               AND current.memory_id = lineage.memory_id
              LEFT JOIN memos.memory_state_transition transition
                ON transition.tenant_id = lineage.tenant_id
               AND transition.memory_id = lineage.memory_id
             WHERE lineage.tenant_id = ? AND lineage.user_id = ? AND lineage.agent_id = ?
               AND lineage.lifecycle_state = 'ACTIVE'
               AND (?::varchar IS NULL OR lineage.memory_type = ?)
               AND (?::uuid IS NULL OR lineage.memory_id > ?)
               AND (
                   ?::varchar IS NULL
                   OR EXISTS (
                       SELECT 1 FROM memos.memory_current_state filtered
                        WHERE filtered.tenant_id = lineage.tenant_id
                          AND filtered.memory_id = lineage.memory_id
                          AND filtered.status = ?
                   )
               )
             GROUP BY lineage.memory_id
             ORDER BY lineage.memory_id
             LIMIT ?
            """,
            (result, row) -> mapSummary(result),
            query.scope().tenantId(),
            query.scope().userId(),
            query.scope().agentId(),
            query.memoryType() == null ? null : query.memoryType().name(),
            query.memoryType() == null ? null : query.memoryType().name(),
            cursor,
            cursor,
            query.status() == null ? null : query.status().name(),
            query.status() == null ? null : query.status().name(),
            query.limit() + 1);
    String nextCursor =
        rows.size() > query.limit()
            ? rows.get(query.limit() - 1).identity().lineageId().value().toString()
            : null;
    return new MemoryLineagePage(
        rows.size() > query.limit() ? rows.subList(0, query.limit()) : rows, nextCursor);
  }

  @Override
  public Optional<MemoryLineageSnapshot> inspect(LineageScope scope, MemoryLineageId lineageId) {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    return loadSnapshot(scope, lineageId, false);
  }

  @Override
  public MemoryAsOfView asOf(MemoryAsOfQuery query) {
    Objects.requireNonNull(query, "query must not be null");
    Optional<MemoryLineageSnapshot> snapshot = inspect(query.scope(), query.lineageId());
    if (snapshot.isEmpty()) {
      return new MemoryAsOfView(query, List.of(), Map.of());
    }
    List<AssertionVersion> versions =
        snapshot.get().versions().stream()
            .filter(version -> !version.recordedAt().isAfter(query.asOfInclusive()))
            .toList();
    return new MemoryAsOfView(query, versions, snapshot.get().statusesAt(query.asOfInclusive()));
  }

  @Override
  public MemoryDiff diff(MemoryDiffQuery query) {
    Objects.requireNonNull(query, "query must not be null");
    Optional<MemoryLineageSnapshot> snapshot = inspect(query.scope(), query.lineageId());
    if (snapshot.isEmpty()) {
      return new MemoryDiff(query, List.of(), List.of());
    }
    return new MemoryDiff(
        query,
        snapshot.get().versions().stream()
            .filter(version -> version.recordedAt().isAfter(query.fromExclusive()))
            .filter(version -> !version.recordedAt().isAfter(query.toInclusive()))
            .toList(),
        snapshot.get().transitions().stream()
            .filter(transition -> transition.occurredAt().isAfter(query.fromExclusive()))
            .filter(transition -> !transition.occurredAt().isAfter(query.toInclusive()))
            .toList());
  }

  @Override
  public List<CandidateForTemporalMaterialization> loadCandidates(ClaimedJob job) {
    Objects.requireNonNull(job, "job must not be null");
    if (job.jobType() != JobType.CANDIDATE_MATERIALIZATION) {
      throw new IllegalArgumentException("candidate materialization job required");
    }
    return jdbc.query(
        """
        SELECT candidate.*, run.provider, run.model_version AS run_model_version,
               run.prompt_version, run.policy_version AS run_policy_version,
               run.schema_version AS run_schema_version, decision.policy_version,
               decision.decided_at
          FROM memos.outbox_job job
          JOIN memos.source_event source
            ON source.tenant_id = job.tenant_id
           AND source.source_event_id = job.source_event_id
          JOIN memos.extraction_run run
            ON run.tenant_id = job.tenant_id
           AND run.run_id = job.aggregate_id
           AND run.source_event_id = job.source_event_id
          JOIN memos.memory_candidate candidate
            ON candidate.tenant_id = run.tenant_id AND candidate.run_id = run.run_id
          JOIN memos.candidate_policy_decision decision
            ON decision.tenant_id = candidate.tenant_id
           AND decision.run_id = candidate.run_id
           AND decision.candidate_id = candidate.candidate_id
           AND decision.ordinal = candidate.ordinal
           AND decision.policy_version = run.policy_version
         WHERE job.tenant_id = ? AND job.job_id = ?
           AND job.job_type = 'CANDIDATE_MATERIALIZATION'
           AND job.aggregate_type = 'EXTRACTION_RUN'
           AND job.source_event_id = ?
           AND job.state = 'CLAIMED' AND job.lease_owner = ? AND job.lease_token = ?
           AND job.lease_expires_at > clock_timestamp()
           AND source.user_id = ? AND source.agent_id = ?
           AND source.deletion_state = 'ACTIVE'
           AND candidate.content_state = 'AVAILABLE'
           AND candidate.proposed_decision = 'REMEMBER'
           AND decision.decision = 'REMEMBER'
         ORDER BY candidate.ordinal
        """,
        JdbcTemporalMemoryAuthority::mapCandidate,
        job.scope().tenantId(),
        job.jobId().value(),
        job.sourceEventId(),
        job.leaseOwner().value(),
        job.leaseToken().value(),
        job.scope().userId(),
        job.scope().agentId());
  }

  @Override
  public Optional<MemoryLineageSnapshot> loadSnapshot(MemoryLineageIdentity proposedIdentity) {
    Objects.requireNonNull(proposedIdentity, "identity must not be null");
    List<LineageRow> matching =
        jdbc.query(
            """
            SELECT * FROM memos.memory_lineage
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ?
               AND lifecycle_state = 'ACTIVE'
               AND memory_type = ? AND subject_kind = ?
               AND subject_label IS NOT DISTINCT FROM ?
               AND predicate = ?
            """,
            (result, row) -> mapLineage(result),
            proposedIdentity.scope().tenantId(),
            proposedIdentity.scope().userId(),
            proposedIdentity.scope().agentId(),
            proposedIdentity.memoryType().name(),
            proposedIdentity.subject().kind().name(),
            proposedIdentity.subject().label(),
            proposedIdentity.predicate());
    if (matching.isEmpty()) {
      return Optional.empty();
    }
    LineageRow persisted = matching.getFirst();
    return loadSnapshot(persisted.identity().scope(), persisted.identity().lineageId(), false);
  }

  @Override
  public TemporalMaterializationCommitResult commit(CommitTemporalMaterialization command) {
    Objects.requireNonNull(command, "command must not be null");
    try {
      return transactions.execute(status -> commitInTransaction(command));
    } catch (OptimisticLockException | DataIntegrityViolationException conflict) {
      return TemporalMaterializationCommitResult.OPTIMISTIC_CONFLICT;
    } catch (LeaseLostRollback lost) {
      return TemporalMaterializationCommitResult.LEASE_LOST;
    }
  }

  private TemporalMaterializationCommitResult commitInTransaction(
      CommitTemporalMaterialization command) {
    ClaimedJob job = command.job();
    Integer completed =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM memos.materialization_result
             WHERE tenant_id = ? AND job_id = ? AND semantic_job_key = ?
               AND outcome = 'TEMPORAL_MATERIALIZED'
            """,
            Integer.class,
            job.scope().tenantId(),
            job.jobId().value(),
            job.semanticJobKey().value());
    if (completed != null && completed == 1) {
      return TemporalMaterializationCommitResult.ALREADY_COMMITTED;
    }
    List<UUID> fenced =
        jdbc.query(
            """
            SELECT job_id FROM memos.outbox_job
             WHERE tenant_id = ? AND job_id = ? AND source_event_id = ?
               AND job_type = 'CANDIDATE_MATERIALIZATION'
               AND aggregate_type = 'EXTRACTION_RUN'
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
      Integer racedCompletion =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM memos.materialization_result
               WHERE tenant_id = ? AND job_id = ? AND semantic_job_key = ?
                 AND outcome = 'TEMPORAL_MATERIALIZED'
              """,
              Integer.class,
              job.scope().tenantId(),
              job.jobId().value(),
              job.semanticJobKey().value());
      if (racedCompletion != null && racedCompletion == 1) {
        return TemporalMaterializationCommitResult.ALREADY_COMMITTED;
      }
      return TemporalMaterializationCommitResult.LEASE_LOST;
    }
    validateCommitBinding(job, command.plannedCandidates());

    List<PlannedCandidateMaterialization> ordered =
        command.plannedCandidates().stream()
            .sorted(
                java.util.Comparator.comparing(
                    planned -> naturalLineageKey(planned.command().lineage())))
            .toList();
    for (PlannedCandidateMaterialization planned : ordered) {
      MaterializeCandidateCommand materialization = planned.command();
      Optional<MemoryLineageSnapshot> resolved = loadSnapshot(materialization.lineage());
      if (resolved.isEmpty()) {
        ensureLineage(materialization.lineage(), materialization.expectedLockVersion());
        resolved = loadSnapshot(materialization.lineage());
      }
      if (resolved.isEmpty()) {
        throw new OptimisticLockException(materialization.expectedLockVersion(), -1);
      }
      MemoryLineageIdentity persisted = resolved.get().identity();
      if (!persisted.lineageId().equals(materialization.lineage().lineageId())) {
        throw new OptimisticLockException(
            materialization.expectedLockVersion(), resolved.get().lockVersion());
      }
      MemoryLineageSnapshot snapshot = lockAndLoad(persisted.scope(), persisted.lineageId());
      if (snapshot.lockVersion() != materialization.expectedLockVersion()) {
        throw new OptimisticLockException(
            materialization.expectedLockVersion(), snapshot.lockVersion());
      }
      TransitionPlan authoritativePlan = planner.planMaterialization(snapshot, materialization);
      persistPlan(
          authoritativePlan,
          persisted,
          planned.command().expectedLockVersion(),
          planned.candidate().provenance(),
          command.projectionPolicyVersion());
    }
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
      throw new LeaseLostRollback();
    }
    jdbc.update(
        """
        INSERT INTO memos.materialization_result (
            tenant_id, semantic_job_key, job_id, source_event_id, outcome,
            handler_version, completed_at, created_at
        ) VALUES (?, ?, ?, ?, 'TEMPORAL_MATERIALIZED', ?,
                  clock_timestamp(), clock_timestamp())
        """,
        job.scope().tenantId(),
        job.semanticJobKey().value(),
        job.jobId().value(),
        job.sourceEventId(),
        command.projectionPolicyVersion());
    return TemporalMaterializationCommitResult.COMMITTED;
  }

  private void validateCommitBinding(
      ClaimedJob job, List<PlannedCandidateMaterialization> plannedCandidates) {
    List<CandidateForTemporalMaterialization> authoritative = loadCandidates(job);
    Map<UUID, CandidateForTemporalMaterialization> byId = new java.util.HashMap<>();
    for (CandidateForTemporalMaterialization candidate : authoritative) {
      if (byId.put(candidate.candidateId().value(), candidate) != null) {
        throw new IllegalStateException("candidate policy authority is not unique");
      }
    }
    if (byId.size() != plannedCandidates.size()) {
      throw new IllegalStateException("temporal commit candidate set mismatch");
    }
    LineageScope jobScope =
        new LineageScope(job.scope().tenantId(), job.scope().userId(), job.scope().agentId());
    for (PlannedCandidateMaterialization planned : plannedCandidates) {
      CandidateForTemporalMaterialization candidate =
          byId.remove(planned.candidate().candidateId().value());
      MaterializeCandidateCommand command = planned.command();
      MemoryCandidateProposal proposal = planned.candidate().proposal();
      if (candidate == null
          || !candidate.equals(planned.candidate())
          || !command.lineage().scope().equals(jobScope)
          || command.lineage().memoryType() != proposal.memoryType()
          || !command.lineage().subject().equals(proposal.subject())
          || !command.lineage().predicate().equals(proposal.predicate())
          || !command.value().equals(proposal.value())
          || !command.normalizedContent().equals(proposal.normalizedContent())
          || !Objects.equals(command.eventTime(), temporalValidity(proposal.eventTime()))
          || !Objects.equals(command.validTime(), temporalValidity(proposal.validInterval()))
          || Double.compare(command.importance(), proposal.importance()) != 0
          || Double.compare(command.confidence(), proposal.confidence()) != 0
          || !command.provenance().equals(candidate.provenance())
          || !command.transitionContext().equals(candidate.transitionContext())) {
        throw new IllegalStateException("temporal commit binding mismatch");
      }
    }
    if (!byId.isEmpty()) {
      throw new IllegalStateException("temporal commit omitted authoritative candidates");
    }
  }

  @Override
  public TemporalMutationResult correct(CorrectionSelection selection) {
    Objects.requireNonNull(selection, "selection must not be null");
    byte[] fingerprint = correctionFingerprint(selection);
    try {
      return transactions.execute(status -> correctInTransaction(selection, fingerprint));
    } catch (OptimisticLockException exception) {
      throw new TemporalMutationException(TemporalMutationFailureKind.STALE_PRECONDITION);
    } catch (InvalidTransitionException exception) {
      throw new TemporalMutationException(TemporalMutationFailureKind.INVALID_TRANSITION);
    } catch (NotFoundMutation exception) {
      throw new TemporalMutationException(TemporalMutationFailureKind.NOT_FOUND);
    }
  }

  private TemporalMutationResult correctInTransaction(
      CorrectionSelection selection, byte[] fingerprint) {
    lockMutationKey(selection.scope(), selection.idempotencyKey());
    Optional<MutationRow> replay = lockMutation(selection.scope(), selection.idempotencyKey());
    if (replay.isPresent()) {
      return replayMutation(replay.get(), fingerprint);
    }
    MemoryLineageSnapshot snapshot =
        loadMutationSnapshot(
            selection.scope(), selection.lineageId(), selection.expectedLockVersion());
    CandidateForTemporalMaterialization candidate =
        loadCorrectionCandidate(selection).orElseThrow(NotFoundMutation::new);
    UUID requestId = UUID.randomUUID();
    insertMutationRequest(
        requestId,
        selection.scope(),
        selection.idempotencyKey(),
        fingerprint,
        "CORRECT",
        selection.lineageId(),
        selection.incorrectVersionId(),
        selection.sourceEventId(),
        selection.candidateId(),
        selection.traceId(),
        selection.requestedAt());
    AssertionProvenance source = candidate.provenance();
    AssertionProvenance corrected =
        new AssertionProvenance(
            source.sourceEventId(),
            source.extractionRunId(),
            source.candidateId(),
            source.extractorVersion(),
            source.promptVersion(),
            source.modelVersion(),
            source.policyVersion(),
            source.schemaVersion(),
            AssertionDerivationRole.CORRECTED,
            source.evidenceSpan());
    MemoryCandidateProposal proposal = candidate.proposal();
    CorrectAssertionCommand command =
        new CorrectAssertionCommand(
            snapshot.identity(),
            selection.incorrectVersionId(),
            proposal.value(),
            proposal.normalizedContent(),
            temporalValidity(proposal.eventTime()),
            temporalValidity(proposal.validInterval()),
            proposal.importance(),
            proposal.confidence(),
            corrected,
            new TransitionContext(
                TransitionActor.USER, TransitionSource.CORRECTION, source.policyVersion()),
            selection.reason(),
            selection.requestedAt(),
            selection.expectedLockVersion());
    TransitionPlan plan = planner.planCorrection(snapshot, command);
    persistPlan(plan, snapshot.identity(), selection.expectedLockVersion(), corrected, null);
    AssertionVersionId resultVersion = plan.appendedVersions().getFirst().versionId();
    StateTransitionId transition = plan.appendedTransitions().getFirst().transitionId();
    completeMutationRequest(
        requestId, plan.resultingSnapshot().lockVersion(), resultVersion, transition);
    return mutationResult(
        TemporalMutationDisposition.APPLIED,
        plan,
        List.of(selection.incorrectVersionId(), resultVersion));
  }

  @Override
  public TemporalMutationResult invalidate(InvalidationSelection selection) {
    Objects.requireNonNull(selection, "selection must not be null");
    byte[] fingerprint = invalidationFingerprint(selection);
    try {
      return transactions.execute(status -> invalidateInTransaction(selection, fingerprint));
    } catch (OptimisticLockException exception) {
      throw new TemporalMutationException(TemporalMutationFailureKind.STALE_PRECONDITION);
    } catch (InvalidTransitionException exception) {
      throw new TemporalMutationException(TemporalMutationFailureKind.INVALID_TRANSITION);
    } catch (NotFoundMutation exception) {
      throw new TemporalMutationException(TemporalMutationFailureKind.NOT_FOUND);
    }
  }

  private TemporalMutationResult invalidateInTransaction(
      InvalidationSelection selection, byte[] fingerprint) {
    lockMutationKey(selection.scope(), selection.idempotencyKey());
    Optional<MutationRow> replay = lockMutation(selection.scope(), selection.idempotencyKey());
    if (replay.isPresent()) {
      return replayMutation(replay.get(), fingerprint);
    }
    MemoryLineageSnapshot snapshot =
        loadMutationSnapshot(
            selection.scope(), selection.lineageId(), selection.expectedLockVersion());
    if (!activeSourceExists(selection.scope(), selection.sourceEventId())) {
      throw new NotFoundMutation();
    }
    AssertionVersion target =
        snapshot.version(selection.versionId()).orElseThrow(NotFoundMutation::new);
    UUID requestId = UUID.randomUUID();
    insertMutationRequest(
        requestId,
        selection.scope(),
        selection.idempotencyKey(),
        fingerprint,
        "INVALIDATE",
        selection.lineageId(),
        selection.versionId(),
        selection.sourceEventId(),
        null,
        selection.traceId(),
        selection.requestedAt());
    InvalidateAssertionCommand command =
        new InvalidateAssertionCommand(
            selection.scope(),
            selection.lineageId(),
            selection.versionId(),
            new TransitionContext(
                TransitionActor.USER,
                TransitionSource.INVALIDATION,
                target.provenance().policyVersion()),
            selection.reason(),
            selection.requestedAt(),
            selection.expectedLockVersion());
    TransitionPlan plan = planner.planInvalidation(snapshot, command);
    persistPlan(plan, snapshot.identity(), selection.expectedLockVersion(), null, null);
    StateTransitionId transition = plan.appendedTransitions().getFirst().transitionId();
    completeMutationRequest(requestId, plan.resultingSnapshot().lockVersion(), null, transition);
    return mutationResult(
        TemporalMutationDisposition.APPLIED, plan, List.of(selection.versionId()));
  }

  private MemoryLineageSnapshot loadMutationSnapshot(
      LineageScope scope, MemoryLineageId lineageId, long expectedLockVersion) {
    MemoryLineageSnapshot snapshot =
        loadSnapshot(scope, lineageId, true).orElseThrow(NotFoundMutation::new);
    if (snapshot.lockVersion() != expectedLockVersion) {
      throw new OptimisticLockException(expectedLockVersion, snapshot.lockVersion());
    }
    return snapshot;
  }

  private Optional<CandidateForTemporalMaterialization> loadCorrectionCandidate(
      CorrectionSelection selection) {
    List<CandidateForTemporalMaterialization> candidates =
        jdbc.query(
            """
            SELECT candidate.*, run.provider, run.model_version AS run_model_version,
                   run.prompt_version, run.policy_version AS run_policy_version,
                   run.schema_version AS run_schema_version, decision.policy_version,
                   decision.decided_at
              FROM memos.memory_candidate candidate
              JOIN memos.extraction_run run
                ON run.tenant_id = candidate.tenant_id AND run.run_id = candidate.run_id
              JOIN memos.source_event source
                ON source.tenant_id = candidate.tenant_id
               AND source.source_event_id = candidate.source_event_id
              JOIN memos.candidate_policy_decision decision
                ON decision.tenant_id = candidate.tenant_id
               AND decision.run_id = candidate.run_id
               AND decision.candidate_id = candidate.candidate_id
               AND decision.ordinal = candidate.ordinal
               AND decision.policy_version = run.policy_version
             WHERE candidate.tenant_id = ? AND candidate.candidate_id = ?
               AND candidate.source_event_id = ?
               AND source.user_id = ? AND source.agent_id = ?
               AND source.deletion_state = 'ACTIVE'
               AND source.actor_type = 'USER'
               AND source.trust_level = 'DIRECT_USER'
               AND source.source_type = 'DIRECT_MEMORY_COMMAND'
               AND candidate.content_state = 'AVAILABLE'
               AND candidate.proposed_decision = 'REMEMBER'
               AND decision.decision = 'REMEMBER'
            """,
            JdbcTemporalMemoryAuthority::mapCandidate,
            selection.scope().tenantId(),
            selection.candidateId(),
            selection.sourceEventId(),
            selection.scope().userId(),
            selection.scope().agentId());
    return candidates.stream().findFirst();
  }

  private boolean activeSourceExists(LineageScope scope, UUID sourceEventId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM memos.source_event
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ?
               AND source_event_id = ? AND deletion_state = 'ACTIVE'
               AND actor_type = 'USER' AND trust_level = 'DIRECT_USER'
               AND source_type = 'DIRECT_MEMORY_COMMAND'
            """,
            Integer.class,
            scope.tenantId(),
            scope.userId(),
            scope.agentId(),
            sourceEventId);
    return count != null && count == 1;
  }

  private Optional<MutationRow> lockMutation(LineageScope scope, String idempotencyKey) {
    return jdbc
        .query(
            """
            SELECT * FROM memos.memory_mutation_request
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ? AND idempotency_key = ?
             FOR UPDATE
            """,
            (result, row) -> mapMutation(result),
            scope.tenantId(),
            scope.userId(),
            scope.agentId(),
            idempotencyKey)
        .stream()
        .findFirst();
  }

  private void lockMutationKey(LineageScope scope, String idempotencyKey) {
    String key =
        String.join("\u0000", scope.tenantId(), scope.userId(), scope.agentId(), idempotencyKey);
    long lockId = ByteBuffer.wrap(sha256Bytes(key)).getLong();
    jdbc.query("SELECT pg_advisory_xact_lock(?)", result -> null, lockId);
  }

  private TemporalMutationResult replayMutation(MutationRow row, byte[] fingerprint) {
    if (!MessageDigest.isEqual(row.requestFingerprint(), fingerprint)
        || !"COMPLETED".equals(row.state())) {
      throw new TemporalMutationException(TemporalMutationFailureKind.IDEMPOTENCY_CONFLICT);
    }
    List<AssertionVersionId> affected = new ArrayList<>();
    affected.add(row.targetVersionId());
    if (row.resultVersionId() != null) {
      affected.add(row.resultVersionId());
    }
    return new TemporalMutationResult(
        TemporalMutationDisposition.REPLAYED,
        dev.memos.domain.temporal.TransitionOperation.INVALIDATE,
        row.lineageId(),
        row.resultLockVersion(),
        affected,
        List.of(row.resultTransitionId()));
  }

  private void insertMutationRequest(
      UUID requestId,
      LineageScope scope,
      String idempotencyKey,
      byte[] fingerprint,
      String mutationType,
      MemoryLineageId lineageId,
      AssertionVersionId targetVersionId,
      UUID sourceEventId,
      UUID candidateId,
      String traceId,
      Instant requestedAt) {
    jdbc.update(
        """
        INSERT INTO memos.memory_mutation_request (
            mutation_request_id, tenant_id, user_id, agent_id, idempotency_key,
            request_fingerprint, mutation_type, memory_id, target_version_id,
            source_event_id, candidate_id, state, trace_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', ?, ?)
        """,
        requestId,
        scope.tenantId(),
        scope.userId(),
        scope.agentId(),
        idempotencyKey,
        fingerprint,
        mutationType,
        lineageId.value(),
        targetVersionId.value(),
        sourceEventId,
        candidateId,
        traceId,
        Timestamp.from(requestedAt));
  }

  private void completeMutationRequest(
      UUID requestId,
      long lockVersion,
      AssertionVersionId resultVersion,
      StateTransitionId transitionId) {
    jdbc.update(
        """
        UPDATE memos.memory_mutation_request
           SET state = 'COMPLETED', result_lock_version = ?, result_version_id = ?,
               result_transition_id = ?, completed_at = clock_timestamp()
         WHERE mutation_request_id = ? AND state = 'STARTED'
        """,
        lockVersion,
        resultVersion == null ? null : resultVersion.value(),
        transitionId.value(),
        requestId);
  }

  private static TemporalMutationResult mutationResult(
      TemporalMutationDisposition disposition,
      TransitionPlan plan,
      List<AssertionVersionId> affected) {
    return new TemporalMutationResult(
        disposition,
        plan.operation(),
        plan.resultingSnapshot().identity().lineageId(),
        plan.resultingSnapshot().lockVersion(),
        affected,
        plan.appendedTransitions().stream().map(AssertionStateTransition::transitionId).toList());
  }

  private static byte[] correctionFingerprint(CorrectionSelection selection) {
    return sha256Bytes(
        String.join(
            "\u0000",
            "CORRECT",
            selection.scope().tenantId(),
            selection.scope().userId(),
            selection.scope().agentId(),
            selection.lineageId().value().toString(),
            selection.incorrectVersionId().value().toString(),
            selection.sourceEventId().toString(),
            selection.candidateId().toString(),
            Long.toString(selection.expectedLockVersion()),
            selection.reason()));
  }

  private static byte[] invalidationFingerprint(InvalidationSelection selection) {
    return sha256Bytes(
        String.join(
            "\u0000",
            "INVALIDATE",
            selection.scope().tenantId(),
            selection.scope().userId(),
            selection.scope().agentId(),
            selection.lineageId().value().toString(),
            selection.versionId().value().toString(),
            selection.sourceEventId().toString(),
            Long.toString(selection.expectedLockVersion()),
            selection.reason()));
  }

  private static String naturalLineageKey(MemoryLineageIdentity identity) {
    return String.join(
        "\u0000",
        identity.scope().tenantId(),
        identity.scope().userId(),
        identity.scope().agentId(),
        identity.memoryType().name(),
        identity.subject().kind().name(),
        identity.subject().label() == null ? "" : identity.subject().label(),
        identity.predicate());
  }

  private static CandidateForTemporalMaterialization mapCandidate(ResultSet result, int ignored)
      throws SQLException {
    UUID candidateId = result.getObject("candidate_id", UUID.class);
    MemoryCandidateProposal proposal =
        new MemoryCandidateProposal(
            ProposalDecision.valueOf(result.getString("proposed_decision")),
            MemoryType.valueOf(result.getString("memory_type")),
            new CandidateSubject(
                SubjectKind.valueOf(result.getString("subject_kind")),
                result.getString("subject_label")),
            result.getString("predicate"),
            candidateValue(result.getString("value_json")),
            result.getString("normalized_content"),
            proposedTime(result.getString("event_time_json")),
            proposedTime(result.getString("valid_interval_json")),
            result.getDouble("importance"),
            result.getDouble("confidence"),
            sensitivity(result.getArray("sensitivity")),
            relations(result.getString("relation_hints_json")));
    AssertionProvenance provenance =
        new AssertionProvenance(
            result.getObject("source_event_id", UUID.class),
            result.getObject("run_id", UUID.class),
            candidateId,
            result.getString("provider"),
            result.getString("prompt_version"),
            result.getString("run_model_version"),
            result.getString("policy_version"),
            result.getString("run_schema_version"),
            AssertionDerivationRole.EXTRACTED,
            null);
    return new CandidateForTemporalMaterialization(
        new CandidateId(candidateId),
        proposal,
        provenance,
        new TransitionContext(
            TransitionActor.WORKER,
            TransitionSource.CANDIDATE_MATERIALIZATION,
            result.getString("policy_version")));
  }

  @SuppressWarnings("unchecked")
  private static ProposedTimeRange proposedTime(String json) {
    if (json == null) {
      return null;
    }
    try {
      Map<String, Object> value = JSON.readValue(json, Map.class);
      return new ProposedTimeRange(
          (String) value.get("original_text"),
          parseInstant(value.get("start_inclusive")),
          parseInstant(value.get("end_exclusive")),
          TemporalPrecision.valueOf((String) value.get("precision")),
          ((Number) value.get("confidence")).doubleValue());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("invalid retained candidate time", exception);
    }
  }

  private static Set<SensitivityCategory> sensitivity(Array array) throws SQLException {
    Object[] values = (Object[]) array.getArray();
    var result = java.util.EnumSet.noneOf(SensitivityCategory.class);
    for (Object value : values) {
      result.add(SensitivityCategory.valueOf(value.toString()));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<CandidateRelation> relations(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      List<Map<String, Object>> values = JSON.readValue(json, List.class);
      return values.stream()
          .map(
              value ->
                  new CandidateRelation(
                      CandidateRelationType.valueOf((String) value.get("type")),
                      (String) value.get("target_subject"),
                      (String) value.get("target_predicate")))
          .toList();
    } catch (RuntimeException exception) {
      throw new IllegalStateException("invalid retained candidate relations", exception);
    }
  }

  private static Instant parseInstant(Object value) {
    return value == null ? null : Instant.parse(value.toString());
  }

  private static TemporalValidity temporalValidity(ProposedTimeRange value) {
    return value == null
        ? null
        : new TemporalValidity(
            value.originalText(),
            value.startInclusive(),
            value.endExclusive(),
            value.precision(),
            value.confidence());
  }

  private static MutationRow mapMutation(ResultSet result) throws SQLException {
    UUID resultVersionId = result.getObject("result_version_id", UUID.class);
    UUID resultTransitionId = result.getObject("result_transition_id", UUID.class);
    Long resultLockVersion = result.getObject("result_lock_version", Long.class);
    return new MutationRow(
        result.getBytes("request_fingerprint"),
        result.getString("mutation_type"),
        result.getString("state"),
        new MemoryLineageId(result.getObject("memory_id", UUID.class)),
        new AssertionVersionId(result.getObject("target_version_id", UUID.class)),
        resultVersionId == null ? null : new AssertionVersionId(resultVersionId),
        resultLockVersion == null ? -1 : resultLockVersion,
        resultTransitionId == null ? null : new StateTransitionId(resultTransitionId));
  }

  private void ensureLineage(MemoryLineageIdentity identity, long expectedLockVersion) {
    if (expectedLockVersion != 0) {
      return;
    }
    jdbc.update(
        """
        INSERT INTO memos.memory_lineage (
            memory_id, tenant_id, user_id, agent_id, memory_type, subject_kind,
            subject_label, predicate, predicate_cardinality, lifecycle_state,
            lock_version, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0,
                  clock_timestamp(), clock_timestamp())
        ON CONFLICT DO NOTHING
        """,
        identity.lineageId().value(),
        identity.scope().tenantId(),
        identity.scope().userId(),
        identity.scope().agentId(),
        identity.memoryType().name(),
        identity.subject().kind().name(),
        identity.subject().label(),
        identity.predicate(),
        identity.cardinality().name());
  }

  private MemoryLineageSnapshot lockAndLoad(LineageScope scope, MemoryLineageId lineageId) {
    return loadSnapshot(scope, lineageId, true)
        .orElseThrow(
            () -> new InvalidTransitionException("memory lineage does not exist in scope"));
  }

  private Optional<MemoryLineageSnapshot> loadSnapshot(
      LineageScope scope, MemoryLineageId lineageId, boolean lock) {
    List<LineageRow> lineages =
        jdbc.query(
            """
            SELECT * FROM memos.memory_lineage
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ? AND memory_id = ?
               AND lifecycle_state = 'ACTIVE'
            """
                + (lock ? " FOR UPDATE" : ""),
            (result, row) -> mapLineage(result),
            scope.tenantId(),
            scope.userId(),
            scope.agentId(),
            lineageId.value());
    if (lineages.isEmpty()) {
      return Optional.empty();
    }
    LineageRow lineage = lineages.getFirst();
    List<AssertionVersion> versions = loadVersions(scope.tenantId(), lineageId);
    List<AssertionStateTransition> transitions = loadTransitions(scope.tenantId(), lineageId);
    return Optional.of(
        new MemoryLineageSnapshot(
            lineage.identity(), lineage.lockVersion(), versions, transitions));
  }

  private List<AssertionVersion> loadVersions(String tenantId, MemoryLineageId lineageId) {
    return jdbc.query(
        """
        SELECT version.*, source.source_event_id, source.extraction_run_id,
               source.candidate_id AS provenance_candidate_id, source.policy_version,
               source.derivation_role, source.evidence_start, source.evidence_end
          FROM memos.memory_version version
          JOIN LATERAL (
              SELECT provenance.*
                FROM memos.memory_source provenance
               WHERE provenance.tenant_id = version.tenant_id
                 AND provenance.memory_id = version.memory_id
                 AND provenance.version_id = version.version_id
               ORDER BY CASE provenance.derivation_role
                            WHEN 'EXTRACTED' THEN 0
                            WHEN 'CORRECTED' THEN 1
                            ELSE 2
                        END,
                        provenance.created_at,
                        provenance.evidence_ordinal,
                        provenance.candidate_id
               LIMIT 1
          ) source ON true
         WHERE version.tenant_id = ? AND version.memory_id = ?
         ORDER BY version.version_number
        """,
        (result, row) -> mapVersion(result),
        tenantId,
        lineageId.value());
  }

  private List<AssertionStateTransition> loadTransitions(
      String tenantId, MemoryLineageId lineageId) {
    return jdbc.query(
        """
        SELECT * FROM memos.memory_state_transition
         WHERE tenant_id = ? AND memory_id = ?
         ORDER BY transition_sequence
        """,
        this::mapTransition,
        tenantId,
        lineageId.value());
  }

  private AssertionStateTransition mapTransition(ResultSet result, int ignoredRow)
      throws SQLException {
    UUID transitionId = result.getObject("transition_id", UUID.class);
    List<StatusChange> changes =
        jdbc.query(
            """
            SELECT * FROM memos.memory_status_change
             WHERE tenant_id = ? AND transition_id = ?
             ORDER BY change_ordinal
            """,
            (change, row) ->
                new StatusChange(
                    new AssertionVersionId(change.getObject("version_id", UUID.class)),
                    enumValue(AssertionStatus.class, change.getString("from_status")),
                    AssertionStatus.valueOf(change.getString("to_status"))),
            result.getString("tenant_id"),
            transitionId);
    return new AssertionStateTransition(
        new StateTransitionId(transitionId),
        new MemoryLineageId(result.getObject("memory_id", UUID.class)),
        result.getLong("transition_sequence"),
        dev.memos.domain.temporal.TransitionOperation.valueOf(result.getString("operation")),
        result.getObject("caused_by_candidate_id", UUID.class),
        uuidArray(result.getArray("related_version_ids")).stream()
            .map(AssertionVersionId::new)
            .toList(),
        changes,
        new TransitionContext(
            TransitionActor.valueOf(result.getString("actor_type")),
            TransitionSource.valueOf(result.getString("transition_source")),
            result.getString("policy_version")),
        result.getString("reason"),
        instant(result, "transaction_time"));
  }

  private void persistPlan(
      TransitionPlan plan,
      MemoryLineageIdentity identity,
      long expectedLockVersion,
      AssertionProvenance causedByProvenance,
      String projectionPolicyVersion) {
    if (plan.replayed()) {
      return;
    }
    for (AssertionVersion version : plan.appendedVersions()) {
      insertVersion(identity.scope().tenantId(), version);
      insertSource(identity.scope().tenantId(), version);
    }
    if (plan.operation() == dev.memos.domain.temporal.TransitionOperation.REINFORCE) {
      if (causedByProvenance == null) {
        throw new IllegalStateException("reinforcement requires provenance");
      }
      for (AssertionStateTransition transition : plan.appendedTransitions()) {
        for (AssertionVersionId relatedVersion : transition.relatedVersions()) {
          insertReinforcementSource(
              identity.scope().tenantId(),
              identity.lineageId(),
              relatedVersion,
              causedByProvenance);
        }
      }
    }
    for (AssertionStateTransition transition : plan.appendedTransitions()) {
      insertTransition(identity, transition);
      applyCurrentState(identity.scope().tenantId(), plan.resultingSnapshot(), transition);
      insertProjectionIntent(
          identity,
          plan.resultingSnapshot(),
          transition,
          projectionPolicyVersion == null
              ? defaultProjectionPolicyVersion
              : projectionPolicyVersion,
          projectionModelVersion);
      insertAudit(identity, transition);
    }
    int updated =
        jdbc.update(
            """
            UPDATE memos.memory_lineage
               SET lock_version = ?, updated_at = clock_timestamp()
             WHERE tenant_id = ? AND memory_id = ? AND lock_version = ?
            """,
            plan.resultingSnapshot().lockVersion(),
            identity.scope().tenantId(),
            identity.lineageId().value(),
            expectedLockVersion);
    if (updated != 1) {
      throw new OptimisticLockException(
          expectedLockVersion, currentLockVersion(identity.scope(), identity.lineageId()));
    }
  }

  private void insertVersion(String tenantId, AssertionVersion version) {
    AssertionProvenance provenance = version.provenance();
    jdbc.update(
        """
        INSERT INTO memos.memory_version (
            version_id, tenant_id, memory_id, version_number, candidate_id, content_state,
            value_json, normalized_content, importance, confidence,
            event_time_original, event_time_start, event_time_end, event_time_precision,
            event_time_confidence, valid_time_original, valid_time_start, valid_time_end,
            valid_time_precision, valid_time_confidence, content_fingerprint,
            extractor_version, prompt_version, policy_version, model_version, schema_version,
            transaction_time
        ) VALUES (?, ?, ?, ?, ?, 'AVAILABLE', ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        version.versionId().value(),
        tenantId,
        version.lineageId().value(),
        version.ordinal(),
        provenance.candidateId(),
        version.value().canonicalJson(),
        version.normalizedContent(),
        version.importance(),
        version.confidence(),
        validityText(version.eventTime()),
        timestamp(validityStart(version.eventTime())),
        timestamp(validityEnd(version.eventTime())),
        validityPrecision(version.eventTime()),
        validityConfidence(version.eventTime()),
        validityText(version.validTime()),
        timestamp(validityStart(version.validTime())),
        timestamp(validityEnd(version.validTime())),
        validityPrecision(version.validTime()),
        validityConfidence(version.validTime()),
        sha256(version.value().canonicalJson() + "\u0000" + version.normalizedContent()),
        provenance.extractorVersion(),
        provenance.promptVersion(),
        provenance.policyVersion(),
        provenance.modelVersion(),
        provenance.schemaVersion(),
        Timestamp.from(version.recordedAt()));
  }

  private void insertSource(String tenantId, AssertionVersion version) {
    AssertionProvenance provenance = version.provenance();
    jdbc.update(
        """
        INSERT INTO memos.memory_source (
            tenant_id, memory_id, version_id, source_event_id, extraction_run_id,
            candidate_id, policy_version, derivation_role, evidence_ordinal,
            evidence_start, evidence_end, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, clock_timestamp())
        """,
        tenantId,
        version.lineageId().value(),
        version.versionId().value(),
        provenance.sourceEventId(),
        provenance.extractionRunId(),
        provenance.candidateId(),
        provenance.policyVersion(),
        provenance.derivationRole().name(),
        provenance.evidenceSpan() == null ? null : provenance.evidenceSpan().startInclusive(),
        provenance.evidenceSpan() == null ? null : provenance.evidenceSpan().endExclusive());
  }

  private void insertReinforcementSource(
      String tenantId,
      MemoryLineageId lineageId,
      AssertionVersionId versionId,
      AssertionProvenance provenance) {
    jdbc.update(
        """
        INSERT INTO memos.memory_source (
            tenant_id, memory_id, version_id, source_event_id, extraction_run_id,
            candidate_id, policy_version, derivation_role, evidence_ordinal,
            evidence_start, evidence_end, created_at
        ) SELECT ?, ?, ?, ?, ?, ?, ?, 'REINFORCED',
                 COALESCE(max(evidence_ordinal), -1) + 1, ?, ?, clock_timestamp()
            FROM memos.memory_source
           WHERE tenant_id = ? AND memory_id = ? AND version_id = ?
        ON CONFLICT (tenant_id, version_id, candidate_id, derivation_role) DO NOTHING
        """,
        tenantId,
        lineageId.value(),
        versionId.value(),
        provenance.sourceEventId(),
        provenance.extractionRunId(),
        provenance.candidateId(),
        provenance.policyVersion(),
        provenance.evidenceSpan() == null ? null : provenance.evidenceSpan().startInclusive(),
        provenance.evidenceSpan() == null ? null : provenance.evidenceSpan().endExclusive(),
        tenantId,
        lineageId.value(),
        versionId.value());
  }

  private void insertTransition(
      MemoryLineageIdentity identity, AssertionStateTransition transition) {
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  """
                INSERT INTO memos.memory_state_transition (
                    transition_id, tenant_id, memory_id, transition_sequence, operation,
                    caused_by_candidate_id, related_version_ids, reason, actor_type, actor_id,
                    transition_source, policy_version, transaction_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
          statement.setObject(1, transition.transitionId().value());
          statement.setString(2, identity.scope().tenantId());
          statement.setObject(3, transition.lineageId().value());
          statement.setLong(4, transition.sequence());
          statement.setString(5, transition.operation().name());
          statement.setObject(6, transition.causedByCandidateId());
          statement.setArray(
              7,
              connection.createArrayOf(
                  "uuid",
                  transition.relatedVersions().stream()
                      .map(AssertionVersionId::value)
                      .toArray(UUID[]::new)));
          statement.setString(8, transition.reason());
          statement.setString(9, transition.context().actor().name());
          statement.setString(10, actorId(identity.scope(), transition.context().actor()));
          statement.setString(11, transition.context().source().name());
          statement.setString(12, transition.context().policyVersion());
          statement.setTimestamp(13, Timestamp.from(transition.occurredAt()));
          return statement;
        });
    for (int ordinal = 0; ordinal < transition.statusChanges().size(); ordinal++) {
      StatusChange change = transition.statusChanges().get(ordinal);
      jdbc.update(
          """
          INSERT INTO memos.memory_status_change (
              tenant_id, transition_id, change_ordinal, memory_id, version_id,
              from_status, to_status
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          identity.scope().tenantId(),
          transition.transitionId().value(),
          ordinal,
          transition.lineageId().value(),
          change.versionId().value(),
          change.fromStatus() == null ? null : change.fromStatus().name(),
          change.toStatus().name());
    }
  }

  private void applyCurrentState(
      String tenantId,
      MemoryLineageSnapshot resultingSnapshot,
      AssertionStateTransition transition) {
    for (int ordinal = 0; ordinal < transition.statusChanges().size(); ordinal++) {
      StatusChange change = transition.statusChanges().get(ordinal);
      AssertionVersion version = resultingSnapshot.version(change.versionId()).orElseThrow();
      jdbc.update(
          """
          INSERT INTO memos.memory_current_state (
              tenant_id, memory_id, version_id, status, effective_valid_from,
              effective_valid_to, transition_id, transition_change_ordinal,
              transition_sequence, rebuilt_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())
          ON CONFLICT (tenant_id, memory_id, version_id) DO UPDATE
             SET status = EXCLUDED.status,
                 effective_valid_from = EXCLUDED.effective_valid_from,
                 effective_valid_to = EXCLUDED.effective_valid_to,
                 transition_id = EXCLUDED.transition_id,
                 transition_change_ordinal = EXCLUDED.transition_change_ordinal,
                 transition_sequence = EXCLUDED.transition_sequence,
                 rebuilt_at = clock_timestamp()
          """,
          tenantId,
          transition.lineageId().value(),
          change.versionId().value(),
          change.toStatus().name(),
          timestamp(validityStart(version.validTime())),
          timestamp(validityEnd(version.validTime())),
          transition.transitionId().value(),
          ordinal,
          transition.sequence());
    }
  }

  private void insertProjectionIntent(
      MemoryLineageIdentity identity,
      MemoryLineageSnapshot snapshot,
      AssertionStateTransition transition,
      String projectionPolicyVersion,
      String projectionModelVersion) {
    SourceIdentity source = sourceIdentity(identity.scope().tenantId(), transition, snapshot);
    String semanticKey =
        "projection/" + identity.lineageId().value() + "/" + transition.transitionId().value();
    UUID jobId = deterministicUuid(identity.scope().tenantId() + "/" + semanticKey);
    int inserted =
        jdbc.update(
            """
            INSERT INTO memos.outbox_job (
                job_id, tenant_id, source_event_id, job_type, aggregate_type, aggregate_id,
                semantic_job_key, policy_version, model_version, state, attempt, max_attempts,
                replay_count, next_attempt_at, payload_reference, trace_id, created_at, updated_at
            ) VALUES (?, ?, ?, 'PROJECTION_BUILD', 'MEMORY_TRANSITION', ?, ?, ?, ?,
                      'PENDING', 0, 3, 0, clock_timestamp(), ?, ?,
                      clock_timestamp(), clock_timestamp())
            ON CONFLICT (tenant_id, semantic_job_key) DO NOTHING
            """,
            jobId,
            identity.scope().tenantId(),
            source.sourceEventId(),
            transition.transitionId().value(),
            semanticKey,
            projectionPolicyVersion,
            projectionModelVersion,
            source.sourceEventId(),
            transition.transitionId().value().toString());
    if (inserted == 0) {
      Integer matching =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM memos.outbox_job
               WHERE tenant_id = ? AND semantic_job_key = ? AND job_id = ?
                 AND job_type = 'PROJECTION_BUILD' AND aggregate_id = ?
              """,
              Integer.class,
              identity.scope().tenantId(),
              semanticKey,
              jobId,
              transition.transitionId().value());
      if (matching == null || matching != 1) {
        throw new IllegalStateException("projection intent identity conflict");
      }
    }
  }

  private SourceIdentity sourceIdentity(
      String tenantId, AssertionStateTransition transition, MemoryLineageSnapshot snapshot) {
    if (transition.causedByCandidateId() != null) {
      return jdbc.queryForObject(
          """
          SELECT candidate.source_event_id, run.model_version
            FROM memos.memory_candidate candidate
            JOIN memos.extraction_run run
              ON run.tenant_id = candidate.tenant_id AND run.run_id = candidate.run_id
           WHERE candidate.tenant_id = ? AND candidate.candidate_id = ?
          """,
          (result, row) ->
              new SourceIdentity(
                  result.getObject("source_event_id", UUID.class),
                  result.getString("model_version")),
          tenantId,
          transition.causedByCandidateId());
    }
    AssertionVersion version =
        snapshot.version(transition.relatedVersions().getFirst()).orElseThrow();
    return new SourceIdentity(
        version.provenance().sourceEventId(), version.provenance().modelVersion());
  }

  private void insertAudit(MemoryLineageIdentity identity, AssertionStateTransition transition) {
    UUID auditId =
        deterministicUuid(
            "audit/" + identity.scope().tenantId() + "/" + transition.transitionId().value());
    jdbc.update(
        """
        INSERT INTO memos.audit_event (
            audit_event_id, tenant_id, user_id, agent_id, actor_type, actor_id,
            action, target_type, target_id, outcome, reason_code, policy_version,
            trace_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'MEMORY_LINEAGE', ?, 'APPLIED', ?, ?, ?, ?)
        """,
        auditId,
        identity.scope().tenantId(),
        identity.scope().userId(),
        identity.scope().agentId(),
        transition.context().actor().name(),
        actorId(identity.scope(), transition.context().actor()),
        transition.operation().name(),
        identity.lineageId().value(),
        transition.operation().name(),
        transition.context().policyVersion(),
        transition.transitionId().value().toString(),
        Timestamp.from(transition.occurredAt()));
  }

  private long currentLockVersion(LineageScope scope, MemoryLineageId lineageId) {
    Long current =
        jdbc.queryForObject(
            """
            SELECT lock_version FROM memos.memory_lineage
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ? AND memory_id = ?
            """,
            Long.class,
            scope.tenantId(),
            scope.userId(),
            scope.agentId(),
            lineageId.value());
    return current == null ? -1 : current;
  }

  private static LineageRow mapLineage(ResultSet result) throws SQLException {
    LineageScope scope =
        new LineageScope(
            result.getString("tenant_id"),
            result.getString("user_id"),
            result.getString("agent_id"));
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(result.getObject("memory_id", UUID.class)),
            scope,
            MemoryType.valueOf(result.getString("memory_type")),
            new CandidateSubject(
                SubjectKind.valueOf(result.getString("subject_kind")),
                result.getString("subject_label")),
            result.getString("predicate"),
            PredicateCardinality.valueOf(result.getString("predicate_cardinality")));
    return new LineageRow(identity, result.getLong("lock_version"));
  }

  private static MemoryLineageSummary mapSummary(ResultSet result) throws SQLException {
    LineageRow lineage = mapLineage(result);
    Map<AssertionStatus, Integer> counts = new EnumMap<>(AssertionStatus.class);
    counts.put(AssertionStatus.CURRENT, result.getInt("current_count"));
    counts.put(AssertionStatus.HISTORICAL, result.getInt("historical_count"));
    counts.put(AssertionStatus.CONFLICTED, result.getInt("conflicted_count"));
    counts.put(AssertionStatus.INVALIDATED, result.getInt("invalidated_count"));
    OffsetDateTime last = result.getObject("last_transition_at", OffsetDateTime.class);
    return new MemoryLineageSummary(
        lineage.identity(), lineage.lockVersion(), counts, last == null ? null : last.toInstant());
  }

  private static AssertionVersion mapVersion(ResultSet result) throws SQLException {
    Integer evidenceStart = result.getObject("evidence_start", Integer.class);
    Integer evidenceEnd = result.getObject("evidence_end", Integer.class);
    AssertionProvenance provenance =
        new AssertionProvenance(
            result.getObject("source_event_id", UUID.class),
            result.getObject("extraction_run_id", UUID.class),
            result.getObject("provenance_candidate_id", UUID.class),
            result.getString("extractor_version"),
            result.getString("prompt_version"),
            result.getString("model_version"),
            result.getString("policy_version"),
            result.getString("schema_version"),
            AssertionDerivationRole.valueOf(result.getString("derivation_role")),
            evidenceStart == null ? null : new EvidenceSpan(evidenceStart, evidenceEnd));
    return new AssertionVersion(
        new AssertionVersionId(result.getObject("version_id", UUID.class)),
        new MemoryLineageId(result.getObject("memory_id", UUID.class)),
        result.getLong("version_number"),
        candidateValue(result.getString("value_json")),
        result.getString("normalized_content"),
        validity(result, "event_time"),
        validity(result, "valid_time"),
        result.getDouble("importance"),
        result.getDouble("confidence"),
        provenance,
        instant(result, "transaction_time"));
  }

  private static TemporalValidity validity(ResultSet result, String prefix) throws SQLException {
    String original = result.getString(prefix + "_original");
    OffsetDateTime start = result.getObject(prefix + "_start", OffsetDateTime.class);
    OffsetDateTime end = result.getObject(prefix + "_end", OffsetDateTime.class);
    String precision = result.getString(prefix + "_precision");
    Number confidence = (Number) result.getObject(prefix + "_confidence");
    if (original == null && start == null && end == null) {
      return null;
    }
    return new TemporalValidity(
        original,
        start == null ? null : start.toInstant(),
        end == null ? null : end.toInstant(),
        TemporalPrecision.valueOf(precision),
        confidence == null ? 0.0 : confidence.doubleValue());
  }

  private static CandidateValue candidateValue(String json) {
    try {
      Object value = JSON.readValue(json, Object.class);
      if (value instanceof String text) {
        return new TextCandidateValue(text);
      }
      if (value instanceof Boolean bool) {
        return new BooleanCandidateValue(bool);
      }
      if (value instanceof Number number) {
        return new NumberCandidateValue(new BigDecimal(number.toString()));
      }
      throw new IllegalStateException("unsupported authoritative candidate value");
    } catch (JacksonException exception) {
      throw new IllegalStateException("invalid authoritative candidate JSON", exception);
    }
  }

  private static List<UUID> uuidArray(Array array) throws SQLException {
    Object[] values = (Object[]) array.getArray();
    List<UUID> result = new ArrayList<>(values.length);
    for (Object value : values) {
      result.add((UUID) value);
    }
    return result;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
    return value == null ? null : Enum.valueOf(type, value);
  }

  private static String actorId(LineageScope scope, TransitionActor actor) {
    return actor == TransitionActor.USER ? scope.userId() : actor.name().toLowerCase();
  }

  private static String validityText(TemporalValidity validity) {
    return validity == null ? null : validity.originalText();
  }

  private static Instant validityStart(TemporalValidity validity) {
    return validity == null ? null : validity.startInclusive();
  }

  private static Instant validityEnd(TemporalValidity validity) {
    return validity == null ? null : validity.endExclusive();
  }

  private static String validityPrecision(TemporalValidity validity) {
    return validity == null ? null : validity.precision().name();
  }

  private static Double validityConfidence(TemporalValidity validity) {
    return validity == null ? null : validity.confidence();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static byte[] sha256(String value) {
    return sha256Bytes(value);
  }

  private static byte[] sha256Bytes(String value) {
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
    return result.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static String requiredText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
    }
    return value;
  }

  private record LineageRow(MemoryLineageIdentity identity, long lockVersion) {}

  private record SourceIdentity(UUID sourceEventId, String modelVersion) {}

  private record MutationRow(
      byte[] requestFingerprint,
      String mutationType,
      String state,
      MemoryLineageId lineageId,
      AssertionVersionId targetVersionId,
      AssertionVersionId resultVersionId,
      long resultLockVersion,
      StateTransitionId resultTransitionId) {}

  private static final class LeaseLostRollback extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class NotFoundMutation extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
