package dev.memos.materialization;

import java.util.Optional;

public interface ProjectionBuildStore {
  Optional<ProjectionBuildPlan> load(ClaimedJob job);

  ProjectionCommitResult commit(CommitProjectionBuild command);
}
