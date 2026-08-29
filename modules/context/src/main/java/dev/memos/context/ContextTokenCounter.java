package dev.memos.context;

public interface ContextTokenCounter {
  ContextTokenCount count(String text);

  String version();
}
