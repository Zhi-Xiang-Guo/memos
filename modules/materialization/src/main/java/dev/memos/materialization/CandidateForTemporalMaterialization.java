package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.TransitionContext;
import java.util.Objects;

/** A retained, policy-approved proposal loaded from the authoritative extraction run. */
public record CandidateForTemporalMaterialization(
    CandidateId candidateId,
    MemoryCandidateProposal proposal,
    AssertionProvenance provenance,
    TransitionContext transitionContext) {
  public CandidateForTemporalMaterialization {
    Objects.requireNonNull(candidateId, "candidateId must not be null");
    Objects.requireNonNull(proposal, "proposal must not be null");
    Objects.requireNonNull(provenance, "provenance must not be null");
    Objects.requireNonNull(transitionContext, "transitionContext must not be null");
    if (!candidateId.value().equals(provenance.candidateId())) {
      throw new IllegalArgumentException("candidate and provenance ids must match");
    }
  }
}
