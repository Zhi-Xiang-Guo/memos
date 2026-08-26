package dev.memos.materialization;

import java.time.Instant;
import java.util.Objects;

public record CommitSkippedExtraction(
    ClaimedJob job, SkippedExtractionReason reason, Instant committedAt) {
  public CommitSkippedExtraction {
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(committedAt, "committedAt must not be null");
  }
}
