package dev.memos.answering.model;

public record ToolCall(String name, String query) {
  public ToolCall {
    if (!"search_memory".equals(name)) throw new IllegalArgumentException("tool is not allowed");
    if (query == null || query.isBlank() || query.length() > 4096)
      throw new IllegalArgumentException("invalid tool query");
  }
}
