package dev.memos.materialization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CommitProjectionBuild(
    ProjectionBuildPlan plan, List<ProjectedVersionBuild> projectedVersions, Instant committedAt) {
  public CommitProjectionBuild {
    Objects.requireNonNull(plan, "plan must not be null");
    projectedVersions =
        List.copyOf(
            Objects.requireNonNull(projectedVersions, "projectedVersions must not be null"));
    if (projectedVersions.size() != plan.items().size()) {
      throw new IllegalArgumentException("projectedVersions must match every source item");
    }
    Objects.requireNonNull(committedAt, "committedAt must not be null");
  }
}
