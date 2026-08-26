package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.governance.PolicyDecision;
import dev.memos.governance.SensitivityAction;
import java.util.Objects;

public record CandidateCommitRecord(
    CandidateId candidateId,
    int ordinal,
    CandidateContentState contentState,
    MemoryCandidateProposal content,
    CandidatePolicyDecisionRecord policyDecision) {
  public CandidateCommitRecord {
    Objects.requireNonNull(candidateId, "candidateId must not be null");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    Objects.requireNonNull(contentState, "contentState must not be null");
    Objects.requireNonNull(policyDecision, "policyDecision must not be null");
    boolean mayRetainContent =
        policyDecision.decision() == PolicyDecision.REMEMBER
            && (policyDecision.sensitivityAction() == SensitivityAction.NONE
                || policyDecision.sensitivityAction() == SensitivityAction.RESTRICT);
    if (contentState == CandidateContentState.AVAILABLE && (!mayRetainContent || content == null)) {
      throw new IllegalArgumentException(
          "AVAILABLE content requires a safe REMEMBER policy decision");
    }
    if (contentState == CandidateContentState.ERASED && content != null) {
      throw new IllegalArgumentException("ERASED candidates must not retain proposal content");
    }
  }
}
