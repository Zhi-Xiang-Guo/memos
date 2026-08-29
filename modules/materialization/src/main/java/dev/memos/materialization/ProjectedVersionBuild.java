package dev.memos.materialization;

import java.util.Objects;

public record ProjectedVersionBuild(ProjectionSourceItem source, ProjectionEmbedding embedding) {
  public ProjectedVersionBuild {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(embedding, "embedding must not be null");
  }
}
