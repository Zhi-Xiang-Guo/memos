package dev.memos.context;

import dev.memos.retrieval.EmbeddingPort;
import dev.memos.retrieval.EmbeddingRequest;
import java.util.Objects;

/** Counts context with the tokenizer exposed by the configured embedding model. */
public final class EmbeddingContextTokenCounter implements ContextTokenCounter {
  private static final String VERSION_PREFIX = "embedding-model:";

  private final EmbeddingPort embeddingPort;
  private final String modelVersion;

  public EmbeddingContextTokenCounter(EmbeddingPort embeddingPort, String modelVersion) {
    this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort must not be null");
    this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    if (modelVersion.isBlank() || modelVersion.length() > 128) {
      throw new IllegalArgumentException("modelVersion must contain 1 to 128 characters");
    }
  }

  @Override
  public ContextTokenCount count(String text) {
    Objects.requireNonNull(text, "text must not be null");
    var result = embeddingPort.embed(new EmbeddingRequest(text, modelVersion));
    if (!modelVersion.equals(result.modelVersion())) {
      throw new IllegalStateException("token counter embedding model version drifted");
    }
    if (result.inputTokens() < 1 || result.inputTokens() > Integer.MAX_VALUE) {
      throw new IllegalStateException("token counter returned an invalid token count");
    }
    return new ContextTokenCount(Math.toIntExact(result.inputTokens()), result.inputTokens(), 1);
  }

  @Override
  public String version() {
    return VERSION_PREFIX + modelVersion;
  }
}
