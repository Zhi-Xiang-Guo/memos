package dev.memos.domain.temporal;

@FunctionalInterface
public interface AssertionDeduplication {
  DeduplicationAssessment assess(
      MaterializeCandidateCommand candidate, AssertionVersion existingVersion);
}
