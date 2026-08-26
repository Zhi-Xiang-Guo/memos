package dev.memos.domain.candidate;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record MemoryCandidateProposal(
    ProposalDecision proposedDecision,
    MemoryType memoryType,
    CandidateSubject subject,
    String predicate,
    CandidateValue value,
    String normalizedContent,
    ProposedTimeRange eventTime,
    ProposedTimeRange validInterval,
    double importance,
    double confidence,
    Set<SensitivityCategory> sensitivity,
    List<CandidateRelation> candidateRelations) {
  private static final Pattern PREDICATE_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

  public MemoryCandidateProposal {
    Objects.requireNonNull(proposedDecision, "proposedDecision must not be null");
    Objects.requireNonNull(memoryType, "memoryType must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
    predicate = CandidateValidation.requireText(predicate, "predicate", 128);
    if (!PREDICATE_PATTERN.matcher(predicate).matches()) {
      throw new IllegalArgumentException("predicate must be a normalized lower-case identifier");
    }
    Objects.requireNonNull(value, "value must not be null");
    normalizedContent =
        CandidateValidation.requireText(normalizedContent, "normalizedContent", 8_192);
    importance = CandidateValidation.requireUnitInterval(importance, "importance");
    confidence = CandidateValidation.requireUnitInterval(confidence, "confidence");
    sensitivity = Set.copyOf(Objects.requireNonNull(sensitivity, "sensitivity must not be null"));
    candidateRelations =
        List.copyOf(
            Objects.requireNonNull(candidateRelations, "candidateRelations must not be null"));
    if (sensitivity.size() > 8) {
      throw new IllegalArgumentException("sensitivity must contain at most 8 values");
    }
    if (candidateRelations.size() > 8) {
      throw new IllegalArgumentException("candidateRelations must contain at most 8 values");
    }
    if (Set.copyOf(candidateRelations).size() != candidateRelations.size()) {
      throw new IllegalArgumentException("candidateRelations must not contain duplicates");
    }
  }
}
