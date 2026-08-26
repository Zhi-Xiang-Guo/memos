package dev.memos.api.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class TemporalMemoryResponses {
  private TemporalMemoryResponses() {}

  record Page(List<Summary> items, String nextCursor) {}

  record Summary(
      String memoryId,
      String memoryType,
      Subject subject,
      String predicate,
      String cardinality,
      long lockVersion,
      Map<String, Integer> statusCounts,
      Instant lastTransitionAt) {}

  record Subject(String kind, String label) {}

  record Inspection(Summary summary, List<Version> versions, List<Transition> transitions) {}

  record History(
      String memoryId, long lockVersion, List<Version> versions, List<Transition> transitions) {}

  record Current(
      String memoryId, long lockVersion, List<Version> current, List<Version> conflicted) {}

  record AsOf(String memoryId, Instant at, List<Version> versions) {}

  record Diff(
      String memoryId,
      Instant fromExclusive,
      Instant toInclusive,
      List<Version> appendedVersions,
      List<Transition> transitions) {}

  record Mutation(
      String memoryId,
      String disposition,
      String operation,
      long lockVersion,
      List<String> affectedVersionIds,
      List<String> transitionIds) {}

  record Version(
      String versionId,
      long version,
      Object value,
      String normalizedContent,
      TemporalValidity eventTime,
      TemporalValidity validTime,
      double importance,
      double confidence,
      String status,
      Provenance provenance,
      Instant recordedAt) {}

  record TemporalValidity(
      String originalText,
      Instant startInclusive,
      Instant endExclusive,
      String precision,
      double confidence) {}

  record Provenance(
      String sourceEventId,
      String extractionRunId,
      String candidateId,
      String extractorVersion,
      String promptVersion,
      String modelVersion,
      String policyVersion,
      String schemaVersion,
      String derivationRole,
      EvidenceSpan evidenceSpan) {}

  record EvidenceSpan(int startInclusive, int endExclusive) {}

  record Transition(
      String transitionId,
      long sequence,
      String operation,
      String causedByCandidateId,
      List<String> relatedVersionIds,
      List<StatusChange> statusChanges,
      String actor,
      String source,
      String policyVersion,
      String reason,
      Instant occurredAt) {}

  record StatusChange(String versionId, String fromStatus, String toStatus) {}
}
