package dev.memos.domain.temporal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TemporalTransitionPlanner {
  private final AssertionDeduplication deduplication;
  private final TemporalIdentityGenerator identifiers;

  public TemporalTransitionPlanner(
      AssertionDeduplication deduplication, TemporalIdentityGenerator identifiers) {
    this.deduplication = Objects.requireNonNull(deduplication, "deduplication must not be null");
    this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
  }

  public TransitionPlan planMaterialization(
      MemoryLineageSnapshot snapshot, MaterializeCandidateCommand command) {
    requireLineage(snapshot, command.lineage());
    if (snapshot.hasCandidate(command.provenance().candidateId())) {
      return ignored(snapshot, true);
    }
    requireLock(snapshot, command.expectedLockVersion());

    Map<AssertionVersionId, AssertionStatus> statuses = snapshot.statuses();
    List<AssertionVersion> retained =
        snapshot.versions().stream()
            .filter(version -> isRetained(statuses.get(version.versionId())))
            .toList();
    for (AssertionVersion existing : retained) {
      DeduplicationAssessment assessment = deduplication.assess(command, existing);
      if (assessment == DeduplicationAssessment.EXACT
          || assessment == DeduplicationAssessment.PARAPHRASE) {
        return reinforce(snapshot, command, existing, assessment);
      }
    }

    AssertionVersion appended = newVersion(snapshot, command);
    if (retained.isEmpty()) {
      return append(
          snapshot,
          TransitionOperation.CREATE,
          List.of(appended),
          List.of(new StatusChange(appended.versionId(), null, AssertionStatus.CURRENT)),
          command.provenance().candidateId(),
          command.transitionContext(),
          "FIRST_ASSERTION",
          command.decidedAt());
    }
    if (snapshot.identity().cardinality() == PredicateCardinality.SET) {
      return append(
          snapshot,
          TransitionOperation.COEXIST,
          List.of(appended),
          List.of(new StatusChange(appended.versionId(), null, AssertionStatus.CURRENT)),
          command.provenance().candidateId(),
          command.transitionContext(),
          "SET_VALUED_DISTINCT",
          command.decidedAt());
    }
    return planSingleValued(snapshot, command, retained, statuses, appended);
  }

  public TransitionPlan planCorrection(
      MemoryLineageSnapshot snapshot, CorrectAssertionCommand command) {
    requireLineage(snapshot, command.lineage());
    if (snapshot.hasCandidate(command.provenance().candidateId())) {
      return ignored(snapshot, true);
    }
    requireLock(snapshot, command.expectedLockVersion());
    AssertionVersion incorrect =
        snapshot
            .version(command.incorrectVersionId())
            .orElseThrow(() -> new InvalidTransitionException("assertion version does not exist"));
    AssertionStatus oldStatus = snapshot.statuses().get(incorrect.versionId());
    if (oldStatus == null || oldStatus == AssertionStatus.INVALIDATED) {
      throw new InvalidTransitionException("an invalidated assertion cannot be corrected again");
    }
    AssertionVersion replacement =
        new AssertionVersion(
            identifiers.nextAssertionVersionId(),
            snapshot.identity().lineageId(),
            snapshot.nextAssertionOrdinal(),
            command.replacementValue(),
            command.replacementNormalizedContent(),
            command.replacementEventTime(),
            command.replacementValidTime(),
            command.replacementImportance(),
            command.replacementConfidence(),
            command.provenance(),
            command.correctedAt());
    List<StatusChange> changes =
        List.of(
            new StatusChange(incorrect.versionId(), oldStatus, AssertionStatus.INVALIDATED),
            new StatusChange(replacement.versionId(), null, oldStatus));
    return append(
        snapshot,
        TransitionOperation.INVALIDATE,
        List.of(replacement),
        changes,
        command.provenance().candidateId(),
        command.transitionContext(),
        "CORRECTION:" + command.reason(),
        command.correctedAt());
  }

  public TransitionPlan planInvalidation(
      MemoryLineageSnapshot snapshot, InvalidateAssertionCommand command) {
    if (!snapshot.identity().scope().equals(command.scope())
        || !snapshot.identity().lineageId().equals(command.lineageId())) {
      throw new InvalidTransitionException("invalidation scope or lineage does not match");
    }
    requireLock(snapshot, command.expectedLockVersion());
    AssertionVersion target =
        snapshot
            .version(command.versionId())
            .orElseThrow(() -> new InvalidTransitionException("assertion version does not exist"));
    AssertionStatus oldStatus = snapshot.statuses().get(target.versionId());
    if (oldStatus == null || oldStatus == AssertionStatus.INVALIDATED) {
      throw new InvalidTransitionException("assertion is already invalidated");
    }
    return append(
        snapshot,
        TransitionOperation.INVALIDATE,
        List.of(),
        List.of(new StatusChange(target.versionId(), oldStatus, AssertionStatus.INVALIDATED)),
        null,
        command.transitionContext(),
        command.reason(),
        command.invalidatedAt());
  }

  private TransitionPlan planSingleValued(
      MemoryLineageSnapshot snapshot,
      MaterializeCandidateCommand command,
      List<AssertionVersion> retained,
      Map<AssertionVersionId, AssertionStatus> statuses,
      AssertionVersion appended) {
    List<TemporalRelation> relations =
        retained.stream()
            .map(
                version ->
                    command.validTime() == null || version.validTime() == null
                        ? TemporalRelation.INDETERMINATE
                        : command.validTime().relationTo(version.validTime()))
            .toList();
    if (relations.stream()
        .anyMatch(
            relation ->
                relation == TemporalRelation.OVERLAPS
                    || relation == TemporalRelation.INDETERMINATE)) {
      return conflict(
          snapshot, command, retained, relations, statuses, appended, "OVERLAP_OR_UNCERTAIN");
    }

    boolean candidateIsLatest =
        relations.stream().noneMatch(relation -> relation == TemporalRelation.BEFORE);
    List<StatusChange> changes = new ArrayList<>();
    for (AssertionVersion existing : retained) {
      AssertionStatus oldStatus = statuses.get(existing.versionId());
      AssertionStatus desired = candidateIsLatest ? AssertionStatus.HISTORICAL : oldStatus;
      if (oldStatus != desired) {
        changes.add(new StatusChange(existing.versionId(), oldStatus, desired));
      }
    }
    changes.add(
        new StatusChange(
            appended.versionId(),
            null,
            candidateIsLatest ? AssertionStatus.CURRENT : AssertionStatus.HISTORICAL));
    return append(
        snapshot,
        TransitionOperation.SUPERSEDE,
        List.of(appended),
        changes,
        command.provenance().candidateId(),
        command.transitionContext(),
        candidateIsLatest ? "LATER_NON_OVERLAPPING" : "BACKFILLED_NON_OVERLAPPING",
        command.decidedAt());
  }

  private TransitionPlan conflict(
      MemoryLineageSnapshot snapshot,
      MaterializeCandidateCommand command,
      List<AssertionVersion> retained,
      List<TemporalRelation> relations,
      Map<AssertionVersionId, AssertionStatus> statuses,
      AssertionVersion appended,
      String reason) {
    List<StatusChange> changes = new ArrayList<>();
    for (int index = 0; index < retained.size(); index++) {
      AssertionVersion existing = retained.get(index);
      TemporalRelation relation = relations.get(index);
      if (relation != TemporalRelation.OVERLAPS && relation != TemporalRelation.INDETERMINATE) {
        continue;
      }
      AssertionStatus oldStatus = statuses.get(existing.versionId());
      if (oldStatus != AssertionStatus.CONFLICTED) {
        changes.add(new StatusChange(existing.versionId(), oldStatus, AssertionStatus.CONFLICTED));
      }
    }
    changes.add(new StatusChange(appended.versionId(), null, AssertionStatus.CONFLICTED));
    return append(
        snapshot,
        TransitionOperation.CONFLICT,
        List.of(appended),
        changes,
        command.provenance().candidateId(),
        command.transitionContext(),
        reason,
        command.decidedAt());
  }

  private TransitionPlan reinforce(
      MemoryLineageSnapshot snapshot,
      MaterializeCandidateCommand command,
      AssertionVersion existing,
      DeduplicationAssessment assessment) {
    AssertionStateTransition transition =
        new AssertionStateTransition(
            identifiers.nextStateTransitionId(),
            snapshot.identity().lineageId(),
            snapshot.nextTransitionSequence(),
            TransitionOperation.REINFORCE,
            command.provenance().candidateId(),
            List.of(existing.versionId()),
            List.of(),
            command.transitionContext(),
            assessment.name(),
            command.decidedAt());
    MemoryLineageSnapshot result = snapshot.append(List.of(), List.of(transition));
    return new TransitionPlan(
        TransitionOperation.REINFORCE, List.of(), List.of(transition), result, false);
  }

  private TransitionPlan append(
      MemoryLineageSnapshot snapshot,
      TransitionOperation operation,
      List<AssertionVersion> versions,
      List<StatusChange> changes,
      java.util.UUID causedByCandidateId,
      TransitionContext context,
      String reason,
      java.time.Instant occurredAt) {
    List<AssertionVersionId> related = new ArrayList<>();
    changes.stream().map(StatusChange::versionId).distinct().forEach(related::add);
    AssertionStateTransition transition =
        new AssertionStateTransition(
            identifiers.nextStateTransitionId(),
            snapshot.identity().lineageId(),
            snapshot.nextTransitionSequence(),
            operation,
            causedByCandidateId,
            related,
            changes,
            context,
            reason,
            occurredAt);
    MemoryLineageSnapshot result = snapshot.append(versions, List.of(transition));
    return new TransitionPlan(operation, versions, List.of(transition), result, false);
  }

  private AssertionVersion newVersion(
      MemoryLineageSnapshot snapshot, MaterializeCandidateCommand command) {
    return new AssertionVersion(
        identifiers.nextAssertionVersionId(),
        snapshot.identity().lineageId(),
        snapshot.nextAssertionOrdinal(),
        command.value(),
        command.normalizedContent(),
        command.eventTime(),
        command.validTime(),
        command.importance(),
        command.confidence(),
        command.provenance(),
        command.decidedAt());
  }

  private static boolean isRetained(AssertionStatus status) {
    return status != null && status != AssertionStatus.INVALIDATED;
  }

  private static TransitionPlan ignored(MemoryLineageSnapshot snapshot, boolean replayed) {
    return new TransitionPlan(TransitionOperation.IGNORE, List.of(), List.of(), snapshot, replayed);
  }

  private static void requireLineage(
      MemoryLineageSnapshot snapshot, MemoryLineageIdentity identity) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (!snapshot.identity().equals(identity)) {
      throw new InvalidTransitionException("candidate lineage does not match snapshot");
    }
  }

  private static void requireLock(MemoryLineageSnapshot snapshot, long expectedLockVersion) {
    if (snapshot.lockVersion() != expectedLockVersion) {
      throw new OptimisticLockException(expectedLockVersion, snapshot.lockVersion());
    }
  }
}
