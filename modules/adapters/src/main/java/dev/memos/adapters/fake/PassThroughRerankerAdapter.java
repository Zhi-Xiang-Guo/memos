package dev.memos.adapters.fake;

import dev.memos.retrieval.RerankerPort;
import java.util.List;

public final class PassThroughRerankerAdapter implements RerankerPort {
  @Override
  public List<String> rerank(String query, List<String> candidates) {
    return List.copyOf(candidates);
  }
}
