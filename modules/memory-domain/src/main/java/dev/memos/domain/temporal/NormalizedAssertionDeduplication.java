package dev.memos.domain.temporal;

import java.util.Locale;

public final class NormalizedAssertionDeduplication implements AssertionDeduplication {
  @Override
  public DeduplicationAssessment assess(
      MaterializeCandidateCommand candidate, AssertionVersion existingVersion) {
    boolean sameValue =
        candidate.value().canonicalJson().equals(existingVersion.value().canonicalJson());
    boolean sameContent =
        normalize(candidate.normalizedContent())
            .equals(normalize(existingVersion.normalizedContent()));
    if (sameValue && sameContent) {
      return DeduplicationAssessment.EXACT;
    }
    return sameValue ? DeduplicationAssessment.PARAPHRASE : DeduplicationAssessment.DISTINCT;
  }

  private static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
