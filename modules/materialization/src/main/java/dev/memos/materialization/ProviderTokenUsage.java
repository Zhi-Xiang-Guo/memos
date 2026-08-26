package dev.memos.materialization;

public record ProviderTokenUsage(long inputTokens, long outputTokens) {
  public ProviderTokenUsage {
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("token counts must not be negative");
    }
  }

  public long totalTokens() {
    return Math.addExact(inputTokens, outputTokens);
  }
}
