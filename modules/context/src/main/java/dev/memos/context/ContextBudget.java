package dev.memos.context;

public record ContextBudget(int maxTokens) {
  public ContextBudget {
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens must be positive");
    }
  }
}
