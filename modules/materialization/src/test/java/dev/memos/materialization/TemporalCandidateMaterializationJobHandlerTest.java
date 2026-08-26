package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.ProposedTimeRange;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TemporalPrecision;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.domain.temporal.AssertionDerivationRole;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.EvidenceSpan;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.NormalizedAssertionDeduplication;
import dev.memos.domain.temporal.PredicateCardinality;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.TemporalIdentityGenerator;
import dev.memos.domain.temporal.TemporalTransitionPlanner;
import dev.memos.domain.temporal.TransitionActor;
import dev.memos.domain.temporal.TransitionContext;
import dev.memos.domain.temporal.TransitionOperation;
import dev.memos.domain.temporal.TransitionSource;
import dev.memos.governance.MemoryScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalCandidateMaterializationJobHandlerTest {
  private static final Instant NOW = Instant.parse("2026-08-27T02:00:00Z");
  private static final Instant EVENT_START = Instant.parse("2026-08-26T10:15:30Z");
  private static final Instant EVENT_END = Instant.parse("2026-08-26T10:15:31Z");
  private static final Instant VALID_START = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant VALID_END = Instant.parse("2026-09-01T00:00:00Z");

  @Test
  void mapsCandidatesToCreateCommandsAndCommitsTheWholeBatchExactlyOnce() throws Exception {
    CandidateForTemporalMaterialization editorTheme =
        candidate(1, "preference.editor.theme", "dark");
    CandidateForTemporalMaterialization responseStyle =
        candidate(2, "preference.response.style", "concise");
    CapturingStore store =
        new CapturingStore(
            List.of(editorTheme, responseStyle), TemporalMaterializationCommitResult.COMMITTED);
    ClaimedJob claimedJob = job();

    JobHandlingResult result =
        handler(store, proposal -> PredicateCardinality.SINGLE).handle(claimedJob);

    assertEquals(JobHandlingResult.COMPLETED_ATOMICALLY, result);
    assertEquals(1, store.commitCalls);
    assertEquals(2, store.committed.plannedCandidates().size());
    assertSame(claimedJob, store.committed.job());
    assertEquals("current-projection-v1", store.committed.projectionPolicyVersion());
    assertEquals(NOW, store.committed.committedAt());

    PlannedCandidateMaterialization first = store.committed.plannedCandidates().getFirst();
    assertSame(editorTheme, first.candidate());
    assertEquals(TransitionOperation.CREATE, first.plan().operation());
    assertEquals(0, first.command().expectedLockVersion());
    assertEquals(NOW, first.command().decidedAt());
    assertEquals(editorTheme.proposal().value(), first.command().value());
    assertEquals(editorTheme.proposal().normalizedContent(), first.command().normalizedContent());
    assertEquals(editorTheme.proposal().importance(), first.command().importance());
    assertEquals(editorTheme.proposal().confidence(), first.command().confidence());
    assertSame(editorTheme.provenance(), first.command().provenance());
    assertSame(editorTheme.transitionContext(), first.command().transitionContext());
    assertEquals(EVENT_START, first.command().eventTime().startInclusive());
    assertEquals(EVENT_END, first.command().eventTime().endExclusive());
    assertEquals("yesterday at 18:15", first.command().eventTime().originalText());
    assertEquals(TemporalPrecision.EXACT, first.command().eventTime().precision());
    assertEquals(0.93, first.command().eventTime().confidence());
    assertEquals(VALID_START, first.command().validTime().startInclusive());
    assertEquals(VALID_END, first.command().validTime().endExclusive());
    assertEquals("August 2026", first.command().validTime().originalText());
    assertEquals(TemporalPrecision.MONTH, first.command().validTime().precision());
    assertEquals(0.88, first.command().validTime().confidence());
    assertEquals(scope().tenantId(), first.command().lineage().scope().tenantId());
    assertEquals(scope().userId(), first.command().lineage().scope().userId());
    assertEquals(scope().agentId(), first.command().lineage().scope().agentId());
    assertEquals(PredicateCardinality.SINGLE, first.command().lineage().cardinality());
    assertSame(editorTheme.provenance(), first.plan().appendedVersions().getFirst().provenance());
    assertEquals(
        first.command().eventTime(), first.plan().appendedVersions().getFirst().eventTime());
    assertEquals(
        first.command().validTime(), first.plan().appendedVersions().getFirst().validTime());
  }

  @Test
  void appliesConfiguredSetCardinalityToThePlannedLineage() throws Exception {
    CapturingStore store =
        new CapturingStore(
            List.of(candidate(1, "preference.cuisine", "sichuan")),
            TemporalMaterializationCommitResult.COMMITTED);
    ConfiguredPredicateCardinalityPolicy policy =
        new ConfiguredPredicateCardinalityPolicy(Set.of("preference.cuisine"));

    handler(store, policy).handle(job());

    assertEquals(
        PredicateCardinality.SET,
        store.committed.plannedCandidates().getFirst().command().lineage().cardinality());
  }

  @Test
  void advancesTheInMemorySnapshotForRepeatedLineageCandidatesInOneBatch() throws Exception {
    CapturingStore store =
        new CapturingStore(
            List.of(
                candidate(1, "preference.editor.theme", "dark"),
                candidate(2, "preference.editor.theme", "light")),
            TemporalMaterializationCommitResult.COMMITTED);

    handler(store, proposal -> PredicateCardinality.SINGLE).handle(job());

    assertEquals(1, store.loadedIdentities.size());
    PlannedCandidateMaterialization first = store.committed.plannedCandidates().get(0);
    PlannedCandidateMaterialization second = store.committed.plannedCandidates().get(1);
    assertEquals(first.command().lineage().lineageId(), second.command().lineage().lineageId());
    assertEquals(0, first.command().expectedLockVersion());
    assertEquals(1, second.command().expectedLockVersion());
    assertEquals(TransitionOperation.CREATE, first.plan().operation());
    assertEquals(TransitionOperation.CONFLICT, second.plan().operation());
  }

  @Test
  void rejectsEmptyRetainedCandidateBatchPermanentlyWithoutCommitting() {
    CapturingStore store =
        new CapturingStore(List.of(), TemporalMaterializationCommitResult.COMMITTED);

    JobHandlingException failure =
        assertThrows(
            JobHandlingException.class,
            () -> handler(store, proposal -> PredicateCardinality.SINGLE).handle(job()));

    assertEquals(JobFailureKind.PERMANENT, failure.kind());
    assertEquals(new JobErrorClass("MISSING_RETAINED_CANDIDATES"), failure.errorClass());
    assertEquals(0, store.commitCalls);
  }

  @Test
  void rejectsWrongJobTypePermanentlyBeforeLoadingCandidates() {
    CapturingStore store =
        new CapturingStore(
            List.of(candidate(1, "preference.editor.theme", "dark")),
            TemporalMaterializationCommitResult.COMMITTED);

    JobHandlingException failure =
        assertThrows(
            JobHandlingException.class,
            () ->
                handler(store, proposal -> PredicateCardinality.SINGLE)
                    .handle(job(JobType.MATERIALIZE_SOURCE)));

    assertEquals(JobFailureKind.PERMANENT, failure.kind());
    assertEquals(new JobErrorClass("UNSUPPORTED_TEMPORAL_JOB_TYPE"), failure.errorClass());
    assertEquals(0, store.loadCandidatesCalls);
    assertEquals(0, store.commitCalls);
  }

  @Test
  void mapsCommittedAlreadyCommittedAndLeaseLostStoreOutcomes() throws Exception {
    assertEquals(
        JobHandlingResult.COMPLETED_ATOMICALLY,
        handleWithCommitResult(TemporalMaterializationCommitResult.COMMITTED));
    assertEquals(
        JobHandlingResult.COMPLETED_ATOMICALLY,
        handleWithCommitResult(TemporalMaterializationCommitResult.ALREADY_COMMITTED));
    assertEquals(
        JobHandlingResult.LEASE_LOST,
        handleWithCommitResult(TemporalMaterializationCommitResult.LEASE_LOST));
  }

  @Test
  void mapsOptimisticCommitConflictToTransientFailure() {
    CapturingStore store =
        new CapturingStore(
            List.of(candidate(1, "preference.editor.theme", "dark")),
            TemporalMaterializationCommitResult.OPTIMISTIC_CONFLICT);

    JobHandlingException failure =
        assertThrows(
            JobHandlingException.class,
            () -> handler(store, proposal -> PredicateCardinality.SINGLE).handle(job()));

    assertEquals(JobFailureKind.TRANSIENT, failure.kind());
    assertEquals(new JobErrorClass("TEMPORAL_OPTIMISTIC_CONFLICT"), failure.errorClass());
    assertEquals(1, store.commitCalls);
  }

  private static JobHandlingResult handleWithCommitResult(
      TemporalMaterializationCommitResult result) throws Exception {
    CapturingStore store =
        new CapturingStore(List.of(candidate(1, "preference.editor.theme", "dark")), result);
    JobHandlingResult handlingResult =
        handler(store, proposal -> PredicateCardinality.SINGLE).handle(job());
    assertEquals(1, store.commitCalls);
    return handlingResult;
  }

  private static TemporalCandidateMaterializationJobHandler handler(
      CapturingStore store, PredicateCardinalityPolicy cardinalityPolicy) {
    return new TemporalCandidateMaterializationJobHandler(
        Clock.fixed(NOW, ZoneOffset.UTC),
        store,
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new SequenceTemporalIdentifiers()),
        cardinalityPolicy,
        (memoryScope, proposal) -> lineageId(proposal.predicate()),
        "current-projection-v1");
  }

  private static CandidateForTemporalMaterialization candidate(
      long ordinal, String predicate, String value) {
    UUID candidateId = new UUID(3, ordinal);
    MemoryCandidateProposal proposal =
        new MemoryCandidateProposal(
            ProposalDecision.REMEMBER,
            MemoryType.SEMANTIC,
            new CandidateSubject(SubjectKind.USER, null),
            predicate,
            new TextCandidateValue(value),
            "The user prefers " + value + ".",
            new ProposedTimeRange(
                "yesterday at 18:15", EVENT_START, EVENT_END, TemporalPrecision.EXACT, 0.93),
            new ProposedTimeRange(
                "August 2026", VALID_START, VALID_END, TemporalPrecision.MONTH, 0.88),
            0.72,
            0.96,
            Set.of(SensitivityCategory.NONE),
            List.of());
    AssertionProvenance provenance =
        new AssertionProvenance(
            job().sourceEventId(),
            new UUID(4, ordinal),
            candidateId,
            "extractor-v1",
            "prompt-v1",
            "model-v1",
            "write-policy-v1",
            "memory-candidate.v1",
            AssertionDerivationRole.EXTRACTED,
            new EvidenceSpan(2, 17));
    return new CandidateForTemporalMaterialization(
        new CandidateId(candidateId),
        proposal,
        provenance,
        new TransitionContext(
            TransitionActor.WORKER,
            TransitionSource.CANDIDATE_MATERIALIZATION,
            "temporal-policy-v1"));
  }

  private static ClaimedJob job() {
    return job(JobType.CANDIDATE_MATERIALIZATION);
  }

  private static ClaimedJob job(JobType jobType) {
    return new ClaimedJob(
        new JobId(new UUID(0, 101)),
        jobType,
        scope(),
        new UUID(0, 102),
        new SemanticJobKey("CANDIDATE_MATERIALIZATION/extraction-run/write-policy-v1"),
        "write-policy-v1",
        "model-v1",
        1,
        5,
        new WorkerId("worker-1"),
        new LeaseToken(new UUID(0, 103)),
        NOW.plus(Duration.ofMinutes(1)),
        "trace-1");
  }

  private static MemoryScope scope() {
    return new MemoryScope("tenant-1", "user-1", "agent-1");
  }

  private static MemoryLineageId lineageId(String predicate) {
    return switch (predicate) {
      case "preference.editor.theme" -> new MemoryLineageId(new UUID(5, 1));
      case "preference.response.style" -> new MemoryLineageId(new UUID(5, 2));
      case "preference.cuisine" -> new MemoryLineageId(new UUID(5, 3));
      default -> throw new IllegalArgumentException("unexpected test predicate");
    };
  }

  private static final class CapturingStore implements TemporalCandidateMaterializationStore {
    private final List<CandidateForTemporalMaterialization> candidates;
    private final TemporalMaterializationCommitResult result;
    private final List<MemoryLineageIdentity> loadedIdentities = new ArrayList<>();
    private int loadCandidatesCalls;
    private int commitCalls;
    private CommitTemporalMaterialization committed;

    private CapturingStore(
        List<CandidateForTemporalMaterialization> candidates,
        TemporalMaterializationCommitResult result) {
      this.candidates = List.copyOf(candidates);
      this.result = result;
    }

    @Override
    public List<CandidateForTemporalMaterialization> loadCandidates(ClaimedJob job) {
      loadCandidatesCalls++;
      return candidates;
    }

    @Override
    public Optional<MemoryLineageSnapshot> loadSnapshot(MemoryLineageIdentity identity) {
      loadedIdentities.add(identity);
      return Optional.empty();
    }

    @Override
    public TemporalMaterializationCommitResult commit(CommitTemporalMaterialization command) {
      commitCalls++;
      committed = command;
      return result;
    }
  }

  private static final class SequenceTemporalIdentifiers implements TemporalIdentityGenerator {
    private long nextVersion = 1;
    private long nextTransition = 1;

    @Override
    public AssertionVersionId nextAssertionVersionId() {
      return new AssertionVersionId(new UUID(6, nextVersion++));
    }

    @Override
    public StateTransitionId nextStateTransitionId() {
      return new StateTransitionId(new UUID(7, nextTransition++));
    }
  }
}
