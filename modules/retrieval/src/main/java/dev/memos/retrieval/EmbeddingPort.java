package dev.memos.retrieval;

import java.util.List;

@FunctionalInterface
public interface EmbeddingPort {
  List<Double> embed(String text);
}
