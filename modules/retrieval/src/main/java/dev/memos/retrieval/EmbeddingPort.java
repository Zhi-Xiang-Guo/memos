package dev.memos.retrieval;

@FunctionalInterface
public interface EmbeddingPort {
  EmbeddingResult embed(EmbeddingRequest request);
}
