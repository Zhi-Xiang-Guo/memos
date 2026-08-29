package dev.memos.api.http;

import java.time.Instant;
import java.util.List;

final class RetrievalResponses {
  private RetrievalResponses() {}

  record Response(Gate gate, Intent intent, Context context, List<Memory> memories, Trace trace) {}

  record Gate(boolean retrieve, String reason) {}

  record Intent(String temporal, Instant targetTime) {}

  record Context(
      String rendered,
      int tokens,
      String tokenCounterVersion,
      int considered,
      int selected,
      boolean truncated,
      List<String> selectedVersionIds) {}

  record Memory(
      String memoryId,
      String versionId,
      String memoryType,
      String subjectKind,
      String subjectLabel,
      String predicate,
      String status,
      String normalizedContent,
      Instant validFrom,
      Instant validTo,
      Instant recordedAt,
      List<String> sourceEventIds,
      double fusedScore,
      Integer rerankRank,
      Watermark watermark,
      List<Component> components) {}

  record Watermark(
      long transitionSequence,
      String projectionPolicyVersion,
      String embeddingModelVersion,
      Instant projectedAt) {}

  record Component(String source, int rank, double rawScore) {}

  record Trace(
      String gateReason,
      String temporalIntent,
      int componentCandidateCount,
      int fusedCandidateCount,
      String rerankOutcome,
      String embeddingProvider,
      String embeddingModelVersion,
      long embeddingInputTokens) {}
}
