package dev.memos.materialization;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProjectionBuildPlan(
    ClaimedJob job,
    UUID memoryId,
    UUID transitionId,
    long transitionSequence,
    List<ProjectionSourceItem> items) {
  public ProjectionBuildPlan {
    Objects.requireNonNull(job, "job must not be null");
    if (job.jobType() != JobType.PROJECTION_BUILD) {
      throw new IllegalArgumentException("projection plan requires a projection job");
    }
    Objects.requireNonNull(memoryId, "memoryId must not be null");
    Objects.requireNonNull(transitionId, "transitionId must not be null");
    if (transitionSequence < 1) {
      throw new IllegalArgumentException("transitionSequence must be positive");
    }
    items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
  }
}
