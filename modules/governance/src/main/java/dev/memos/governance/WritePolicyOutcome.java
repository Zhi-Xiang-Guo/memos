package dev.memos.governance;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import java.util.List;
import java.util.Objects;

public record WritePolicyOutcome(
    MemoryCandidateProposal effectiveProposal,
    PolicyDecision decision,
    SensitivityAction sensitivityAction,
    boolean restricted,
    List<WritePolicyReason> reasons) {
  public WritePolicyOutcome {
    Objects.requireNonNull(effectiveProposal, "effectiveProposal must not be null");
    Objects.requireNonNull(decision, "decision must not be null");
    Objects.requireNonNull(sensitivityAction, "sensitivityAction must not be null");
    reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
    if (reasons.isEmpty()) {
      throw new IllegalArgumentException("at least one reason is required");
    }
    if (restricted != (sensitivityAction == SensitivityAction.RESTRICT)) {
      throw new IllegalArgumentException("restricted must match the sensitivity action");
    }
  }
}
