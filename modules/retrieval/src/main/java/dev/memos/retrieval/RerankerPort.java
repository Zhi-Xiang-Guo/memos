package dev.memos.retrieval;

import java.util.List;

@FunctionalInterface
public interface RerankerPort {
  List<String> rerank(String query, List<String> candidates);
}
