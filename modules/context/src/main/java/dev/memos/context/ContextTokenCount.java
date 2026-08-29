package dev.memos.context;

public record ContextTokenCount(int tokens, long providerInputTokens, int providerCalls) {
  public ContextTokenCount {
    if (tokens < 1 || providerInputTokens < 0 || providerCalls < 0) {
      throw new IllegalArgumentException("context token count values are invalid");
    }
    if (providerCalls == 0 && providerInputTokens != 0) {
      throw new IllegalArgumentException("local token counts cannot report provider input");
    }
  }

  public static ContextTokenCount local(int tokens) {
    return new ContextTokenCount(tokens, 0, 0);
  }
}
