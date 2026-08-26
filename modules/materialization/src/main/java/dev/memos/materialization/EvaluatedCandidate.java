package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.governance.WritePolicyOutcome;
import java.util.Objects;

public record EvaluatedCandidate(
    CandidateId candidateId,
    int ordinal,
    MemoryCandidateProposal proposal,
    WritePolicyOutcome policyOutcome) {
  public EvaluatedCandidate {
    Objects.requireNonNull(candidateId, "candidateId must not be null");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    Objects.requireNonNull(proposal, "proposal must not be null");
    Objects.requireNonNull(policyOutcome, "policyOutcome must not be null");
  }
}
