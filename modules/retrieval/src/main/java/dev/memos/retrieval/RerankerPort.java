package dev.memos.retrieval;

@FunctionalInterface
public interface RerankerPort {
  RerankResult rerank(RerankRequest request);
}
