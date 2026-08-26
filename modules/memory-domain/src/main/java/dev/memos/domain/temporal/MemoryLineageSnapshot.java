package dev.memos.domain.temporal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MemoryLineageSnapshot(
    MemoryLineageIdentity identity,
    long lockVersion,
    List<AssertionVersion> versions,
    List<AssertionStateTransition> transitions) {
  public MemoryLineageSnapshot {
    Objects.requireNonNull(identity, "identity must not be null");
    if (lockVersion < 0) {
      throw new IllegalArgumentException("lockVersion must not be negative");
    }
    versions = List.copyOf(Objects.requireNonNull(versions, "versions must not be null"));
    transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions must not be null"));
    validate(identity, lockVersion, versions, transitions);
  }

  public static MemoryLineageSnapshot empty(MemoryLineageIdentity identity) {
    return new MemoryLineageSnapshot(identity, 0, List.of(), List.of());
  }

  public Map<AssertionVersionId, AssertionStatus> statuses() {
    return statusesAt(null);
  }

  public Map<AssertionVersionId, AssertionStatus> statusesAt(Instant asOfInclusive) {
    return replayStatuses(transitions, asOfInclusive);
  }

  private static Map<AssertionVersionId, AssertionStatus> replayStatuses(
      List<AssertionStateTransition> transitions, Instant asOfInclusive) {
    Map<AssertionVersionId, AssertionStatus> statuses = new LinkedHashMap<>();
    for (AssertionStateTransition transition : transitions) {
      if (asOfInclusive != null && transition.occurredAt().isAfter(asOfInclusive)) {
        continue;
      }
      for (StatusChange change : transition.statusChanges()) {
        AssertionStatus actual = statuses.get(change.versionId());
        if (actual != change.fromStatus()) {
          throw new InvalidTransitionException(
              "transition status does not match prior state for " + change.versionId());
        }
        statuses.put(change.versionId(), change.toStatus());
      }
    }
    return Collections.unmodifiableMap(statuses);
  }

  public Optional<AssertionVersion> version(AssertionVersionId versionId) {
    return versions.stream().filter(version -> version.versionId().equals(versionId)).findFirst();
  }

  public boolean hasCandidate(UUID candidateId) {
    Objects.requireNonNull(candidateId, "candidateId must not be null");
    return versions.stream()
            .anyMatch(version -> version.provenance().candidateId().equals(candidateId))
        || transitions.stream()
            .anyMatch(transition -> candidateId.equals(transition.causedByCandidateId()));
  }

  public long nextAssertionOrdinal() {
    return versions.size() + 1L;
  }

  public long nextTransitionSequence() {
    return transitions.size() + 1L;
  }

  public MemoryLineageSnapshot append(
      List<AssertionVersion> appendedVersions, List<AssertionStateTransition> appendedTransitions) {
    List<AssertionVersion> allVersions = new ArrayList<>(versions);
    allVersions.addAll(appendedVersions);
    List<AssertionStateTransition> allTransitions = new ArrayList<>(transitions);
    allTransitions.addAll(appendedTransitions);
    return new MemoryLineageSnapshot(identity, lockVersion + 1, allVersions, allTransitions);
  }

  private static void validate(
      MemoryLineageIdentity identity,
      long lockVersion,
      List<AssertionVersion> versions,
      List<AssertionStateTransition> transitions) {
    if (versions.stream().map(AssertionVersion::versionId).distinct().count() != versions.size()) {
      throw new IllegalArgumentException("version ids must be unique");
    }
    if (versions.stream().map(version -> version.provenance().candidateId()).distinct().count()
        != versions.size()) {
      throw new IllegalArgumentException("materialized candidate ids must be unique");
    }
    for (int index = 0; index < versions.size(); index++) {
      AssertionVersion version = versions.get(index);
      if (!version.lineageId().equals(identity.lineageId())) {
        throw new IllegalArgumentException("assertion belongs to a different lineage");
      }
      if (version.ordinal() != index + 1L) {
        throw new IllegalArgumentException("assertion ordinals must be contiguous");
      }
    }
    if (transitions.stream().map(AssertionStateTransition::transitionId).distinct().count()
        != transitions.size()) {
      throw new IllegalArgumentException("transition ids must be unique");
    }
    for (int index = 0; index < transitions.size(); index++) {
      AssertionStateTransition transition = transitions.get(index);
      if (!transition.lineageId().equals(identity.lineageId())) {
        throw new IllegalArgumentException("transition belongs to a different lineage");
      }
      if (transition.sequence() != index + 1L) {
        throw new IllegalArgumentException("transition sequences must be contiguous");
      }
      for (AssertionVersionId related : transition.relatedVersions()) {
        if (versions.stream().noneMatch(version -> version.versionId().equals(related))) {
          throw new IllegalArgumentException("transition references an unknown assertion");
        }
      }
    }
    replayStatuses(transitions, null);
  }
}
