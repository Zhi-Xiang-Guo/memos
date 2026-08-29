package dev.memos.materialization;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Embeds outside a transaction, then delegates one fenced projection commit. */
public final class ProjectionBuildJobHandler implements MaterializationJobHandler {
  private final Clock clock;
  private final ProjectionBuildStore store;
  private final ProjectionEmbeddingPort embeddings;

  public ProjectionBuildJobHandler(
      Clock clock, ProjectionBuildStore store, ProjectionEmbeddingPort embeddings) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings must not be null");
  }

  @Override
  public JobHandlingResult handle(ClaimedJob job) throws JobHandlingException {
    Objects.requireNonNull(job, "job must not be null");
    if (job.jobType() != JobType.PROJECTION_BUILD) {
      throw JobHandlingException.permanentFailure("UNSUPPORTED_PROJECTION_JOB_TYPE");
    }
    ProjectionBuildPlan plan =
        store.load(job).orElseThrow(() -> permanent("MISSING_PROJECTION_SOURCE"));
    List<ProjectedVersionBuild> projected = new ArrayList<>(plan.items().size());
    for (ProjectionSourceItem item : plan.items()) {
      ProjectionEmbedding embedding =
          embeddings.embed(
              new ProjectionEmbeddingRequest(item.normalizedContent(), job.modelVersion()));
      if (!embedding.modelVersion().equals(job.modelVersion())) {
        throw JobHandlingException.permanentFailure("EMBEDDING_MODEL_VERSION_MISMATCH");
      }
      projected.add(new ProjectedVersionBuild(item, embedding));
    }
    ProjectionCommitResult result =
        store.commit(new CommitProjectionBuild(plan, projected, clock.instant()));
    return switch (result) {
      case COMMITTED, SUPERSEDED, ALREADY_COMMITTED -> JobHandlingResult.COMPLETED_ATOMICALLY;
      case LEASE_LOST -> JobHandlingResult.LEASE_LOST;
    };
  }

  private static JobHandlingException permanent(String errorClass) {
    return JobHandlingException.permanentFailure(errorClass);
  }
}
