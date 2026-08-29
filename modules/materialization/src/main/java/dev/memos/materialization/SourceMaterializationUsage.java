package dev.memos.materialization;

/** Content-free provider usage attributable to one source materialization chain. */
public record SourceMaterializationUsage(
    boolean complete, long inputTokens, long outputTokens, long embeddingTokens, long modelCalls) {
  public SourceMaterializationUsage {
    if (inputTokens < 0 || outputTokens < 0 || embeddingTokens < 0 || modelCalls < 0) {
      throw new IllegalArgumentException("materialization usage must not be negative");
    }
  }

  public static SourceMaterializationUsage unavailable() {
    return new SourceMaterializationUsage(false, 0, 0, 0, 0);
  }
}
