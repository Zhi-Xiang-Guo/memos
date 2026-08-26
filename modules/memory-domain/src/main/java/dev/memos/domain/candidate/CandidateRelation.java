package dev.memos.domain.candidate;

import java.util.Objects;
import java.util.regex.Pattern;

public record CandidateRelation(
    CandidateRelationType type, String targetSubject, String targetPredicate) {
  private static final Pattern PREDICATE_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

  public CandidateRelation {
    Objects.requireNonNull(type, "type must not be null");
    targetSubject = CandidateValidation.requireText(targetSubject, "targetSubject", 256);
    targetPredicate = CandidateValidation.requireText(targetPredicate, "targetPredicate", 128);
    if (!PREDICATE_PATTERN.matcher(targetPredicate).matches()) {
      throw new IllegalArgumentException(
          "targetPredicate must be a normalized lower-case identifier");
    }
  }
}
