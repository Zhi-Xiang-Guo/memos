package dev.memos.materialization;

import dev.memos.domain.temporal.MaterializeCandidateCommand;
import dev.memos.domain.temporal.TransitionPlan;
import java.util.Objects;

public record PlannedCandidateMaterialization(
    CandidateForTemporalMaterialization candidate,
    MaterializeCandidateCommand command,
    TransitionPlan plan) {
  public PlannedCandidateMaterialization {
    Objects.requireNonNull(candidate, "candidate must not be null");
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    if (!candidate.candidateId().value().equals(command.provenance().candidateId())) {
      throw new IllegalArgumentException("planned command must retain candidate provenance");
    }
  }
}
