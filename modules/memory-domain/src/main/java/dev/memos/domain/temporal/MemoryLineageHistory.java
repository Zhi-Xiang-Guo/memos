package dev.memos.domain.temporal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class MemoryLineageHistory {
  public MemoryDiff diff(MemoryLineageSnapshot snapshot, MemoryDiffQuery query) {
    requireScope(snapshot, query.scope(), query.lineageId());
    List<AssertionStateTransition> transitions =
        snapshot.transitions().stream()
            .filter(transition -> transition.occurredAt().isAfter(query.fromExclusive()))
            .filter(transition -> !transition.occurredAt().isAfter(query.toInclusive()))
            .toList();
    Set<AssertionVersionId> appendedIds =
        transitions.stream()
            .flatMap(transition -> transition.statusChanges().stream())
            .filter(change -> change.fromStatus() == null)
            .map(StatusChange::versionId)
            .collect(Collectors.toUnmodifiableSet());
    List<AssertionVersion> versions =
        snapshot.versions().stream()
            .filter(version -> appendedIds.contains(version.versionId()))
            .toList();
    return new MemoryDiff(query, versions, transitions);
  }

  public MemoryAsOfView asOf(MemoryLineageSnapshot snapshot, MemoryAsOfQuery query) {
    requireScope(snapshot, query.scope(), query.lineageId());
    Map<AssertionVersionId, AssertionStatus> statuses = snapshot.statusesAt(query.asOfInclusive());
    List<AssertionVersion> versions =
        snapshot.versions().stream()
            .filter(version -> !version.recordedAt().isAfter(query.asOfInclusive()))
            .filter(version -> statuses.containsKey(version.versionId()))
            .toList();
    return new MemoryAsOfView(query, versions, statuses);
  }

  private static void requireScope(
      MemoryLineageSnapshot snapshot, LineageScope scope, MemoryLineageId lineageId) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    if (!snapshot.identity().scope().equals(scope)
        || !snapshot.identity().lineageId().equals(lineageId)) {
      throw new InvalidTransitionException("query scope or lineage does not match snapshot");
    }
  }
}
