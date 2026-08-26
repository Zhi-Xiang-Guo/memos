package dev.memos.domain.candidate;

import java.util.Objects;

public record CandidateSubject(SubjectKind kind, String label) {
  public CandidateSubject {
    Objects.requireNonNull(kind, "kind must not be null");
    if (label != null) {
      label = CandidateValidation.requireText(label, "label", 500);
    }
    if (kind == SubjectKind.PROJECT && label == null) {
      throw new IllegalArgumentException("PROJECT subjects require a label");
    }
  }
}
