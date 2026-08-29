package dev.memos.adapters.fake;

import dev.memos.retrieval.RerankRequest;
import dev.memos.retrieval.RerankResult;
import dev.memos.retrieval.RerankerPort;

public final class PassThroughRerankerAdapter implements RerankerPort {
  public static final String MODEL_VERSION = "pass-through-v1";

  @Override
  public RerankResult rerank(RerankRequest request) {
    if (!MODEL_VERSION.equals(request.modelVersion())) {
      throw new IllegalArgumentException("requested reranker model is not configured");
    }
    return new RerankResult(
        request.candidates().stream().map(candidate -> candidate.versionId()).toList(),
        "deterministic-local",
        request.modelVersion(),
        request.query().codePointCount(0, request.query().length()));
  }
}
