package dev.memos.materialization;

import dev.memos.governance.PolicyDecision;
import dev.memos.governance.SensitivityAction;
import dev.memos.governance.WritePolicyReason;
import java.util.List;
import java.util.Objects;

public record CandidatePolicyDecisionRecord(
    PolicyDecision decision,
    SensitivityAction sensitivityAction,
    List<WritePolicyReason> reasons,
    String policyVersion) {
  public CandidatePolicyDecisionRecord {
    Objects.requireNonNull(decision, "decision must not be null");
    Objects.requireNonNull(sensitivityAction, "sensitivityAction must not be null");
    reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
    if (reasons.isEmpty()) {
      throw new IllegalArgumentException("at least one reason is required");
    }
    policyVersion = MaterializationTextValidation.requireText(policyVersion, "policyVersion", 128);
  }
}
