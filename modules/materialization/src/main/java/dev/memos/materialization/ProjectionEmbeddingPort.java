package dev.memos.materialization;

@FunctionalInterface
public interface ProjectionEmbeddingPort {
  ProjectionEmbedding embed(ProjectionEmbeddingRequest request);
}
