package dev.memos.materialization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CommitTemporalMaterialization(
    ClaimedJob job,
    List<PlannedCandidateMaterialization> plannedCandidates,
    String projectionPolicyVersion,
    Instant committedAt) {
  public CommitTemporalMaterialization {
    Objects.requireNonNull(job, "job must not be null");
    if (job.jobType() != JobType.CANDIDATE_MATERIALIZATION) {
      throw new IllegalArgumentException(
          "temporal commit requires a candidate materialization job");
    }
    plannedCandidates =
        List.copyOf(
            Objects.requireNonNull(plannedCandidates, "plannedCandidates must not be null"));
    if (plannedCandidates.isEmpty()) {
      throw new IllegalArgumentException("plannedCandidates must not be empty");
    }
    projectionPolicyVersion =
        MaterializationTextValidation.requireText(
            projectionPolicyVersion, "projectionPolicyVersion", 128);
    Objects.requireNonNull(committedAt, "committedAt must not be null");
  }
}
