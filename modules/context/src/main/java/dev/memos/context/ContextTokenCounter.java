package dev.memos.context;

public interface ContextTokenCounter {
  int count(String text);

  String version();
}
