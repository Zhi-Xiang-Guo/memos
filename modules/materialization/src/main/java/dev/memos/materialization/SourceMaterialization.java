package dev.memos.materialization;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SourceMaterialization(UUID sourceEventId, List<MaterializationJob> jobs) {
  public SourceMaterialization {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    jobs =
        Objects.requireNonNull(jobs, "jobs must not be null").stream()
            .sorted(
                Comparator.comparing(MaterializationJob::jobType)
                    .thenComparing(MaterializationJob::createdAt)
                    .thenComparing(job -> job.jobId().value()))
            .toList();
    if (jobs.isEmpty()) {
      throw new IllegalArgumentException("source materialization requires at least one job");
    }
    if (jobs.stream().anyMatch(job -> !sourceEventId.equals(job.sourceEventId()))) {
      throw new IllegalArgumentException("all jobs must belong to the source event");
    }
  }

  public SourceMaterializationState state() {
    if (jobs.stream().anyMatch(job -> job.state() == JobState.DEAD)) {
      return SourceMaterializationState.FAILED;
    }
    if (jobs.stream().allMatch(job -> job.state() == JobState.SUCCEEDED)) {
      return SourceMaterializationState.SUCCEEDED;
    }
    return SourceMaterializationState.PROCESSING;
  }

  public Instant createdAt() {
    return jobs.stream().map(MaterializationJob::createdAt).min(Instant::compareTo).orElseThrow();
  }

  public Instant updatedAt() {
    return jobs.stream().map(MaterializationJob::updatedAt).max(Instant::compareTo).orElseThrow();
  }

  public Instant settledAt() {
    if (state() == SourceMaterializationState.PROCESSING) {
      return null;
    }
    return jobs.stream()
        .map(MaterializationJob::completedAt)
        .filter(Objects::nonNull)
        .max(Instant::compareTo)
        .orElseThrow();
  }
}
