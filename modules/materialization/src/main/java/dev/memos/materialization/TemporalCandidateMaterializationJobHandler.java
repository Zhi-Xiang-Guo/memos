package dev.memos.materialization;

import dev.memos.domain.candidate.ProposedTimeRange;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MaterializeCandidateCommand;
import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.TemporalTransitionPlanner;
import dev.memos.domain.temporal.TemporalValidity;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plans deterministic candidate transitions and delegates one fenced authoritative commit. */
public final class TemporalCandidateMaterializationJobHandler implements MaterializationJobHandler {
  private final Clock clock;
  private final TemporalCandidateMaterializationStore store;
  private final TemporalTransitionPlanner planner;
  private final PredicateCardinalityPolicy cardinalityPolicy;
  private final TemporalLineageIdentifier lineageIdentifier;
  private final String projectionPolicyVersion;

  public TemporalCandidateMaterializationJobHandler(
      Clock clock,
      TemporalCandidateMaterializationStore store,
      TemporalTransitionPlanner planner,
      PredicateCardinalityPolicy cardinalityPolicy,
      TemporalLineageIdentifier lineageIdentifier,
      String projectionPolicyVersion) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.cardinalityPolicy =
        Objects.requireNonNull(cardinalityPolicy, "cardinalityPolicy must not be null");
    this.lineageIdentifier =
        Objects.requireNonNull(lineageIdentifier, "lineageIdentifier must not be null");
    this.projectionPolicyVersion =
        MaterializationTextValidation.requireText(
            projectionPolicyVersion, "projectionPolicyVersion", 128);
  }

  @Override
  public JobHandlingResult handle(ClaimedJob job) throws JobHandlingException {
    Objects.requireNonNull(job, "job must not be null");
    if (job.jobType() != JobType.CANDIDATE_MATERIALIZATION) {
      throw JobHandlingException.permanentFailure("UNSUPPORTED_TEMPORAL_JOB_TYPE");
    }
    List<CandidateForTemporalMaterialization> candidates = store.loadCandidates(job);
    if (candidates.isEmpty()) {
      throw JobHandlingException.permanentFailure("MISSING_RETAINED_CANDIDATES");
    }

    List<PlannedCandidateMaterialization> planned = new ArrayList<>();
    Map<NaturalLineageKey, MemoryLineageSnapshot> batchSnapshots = new HashMap<>();
    for (CandidateForTemporalMaterialization candidate : candidates) {
      var proposal = candidate.proposal();
      MemoryLineageIdentity proposedIdentity =
          new MemoryLineageIdentity(
              lineageIdentifier.identify(job.scope(), proposal),
              new LineageScope(job.scope().tenantId(), job.scope().userId(), job.scope().agentId()),
              proposal.memoryType(),
              proposal.subject(),
              proposal.predicate(),
              cardinalityPolicy.cardinality(proposal));
      NaturalLineageKey lineageKey = NaturalLineageKey.from(proposedIdentity);
      MemoryLineageSnapshot snapshot = batchSnapshots.get(lineageKey);
      if (snapshot == null) {
        snapshot =
            store
                .loadSnapshot(proposedIdentity)
                .orElseGet(() -> MemoryLineageSnapshot.empty(proposedIdentity));
      }
      MemoryLineageIdentity resolvedIdentity = snapshot.identity();
      MaterializeCandidateCommand command =
          new MaterializeCandidateCommand(
              resolvedIdentity,
              proposal.value(),
              proposal.normalizedContent(),
              temporal(proposal.eventTime()),
              temporal(proposal.validInterval()),
              proposal.importance(),
              proposal.confidence(),
              candidate.provenance(),
              candidate.transitionContext(),
              clock.instant(),
              snapshot.lockVersion());
      var plan = planner.planMaterialization(snapshot, command);
      planned.add(new PlannedCandidateMaterialization(candidate, command, plan));
      batchSnapshots.put(lineageKey, plan.resultingSnapshot());
    }

    TemporalMaterializationCommitResult result =
        store.commit(
            new CommitTemporalMaterialization(
                job, planned, projectionPolicyVersion, clock.instant()));
    return switch (result) {
      case COMMITTED, ALREADY_COMMITTED -> JobHandlingResult.COMPLETED_ATOMICALLY;
      case LEASE_LOST -> JobHandlingResult.LEASE_LOST;
      case OPTIMISTIC_CONFLICT ->
          throw JobHandlingException.transientFailure("TEMPORAL_OPTIMISTIC_CONFLICT");
    };
  }

  private static TemporalValidity temporal(ProposedTimeRange time) {
    if (time == null) {
      return null;
    }
    return new TemporalValidity(
        time.originalText(),
        time.startInclusive(),
        time.endExclusive(),
        time.precision(),
        time.confidence());
  }

  private record NaturalLineageKey(
      LineageScope scope,
      dev.memos.domain.candidate.MemoryType memoryType,
      dev.memos.domain.candidate.CandidateSubject subject,
      String predicate) {
    private static NaturalLineageKey from(MemoryLineageIdentity identity) {
      return new NaturalLineageKey(
          identity.scope(), identity.memoryType(), identity.subject(), identity.predicate());
    }
  }
}
