package dev.memos.context;

import java.util.Objects;

/** Conservative deterministic counter for local tests; provider tokenizers remain adapters. */
public final class CodePointTokenCounter implements ContextTokenCounter {
  public static final String VERSION = "unicode-codepoint-v1";

  @Override
  public int count(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return text.codePointCount(0, text.length());
  }

  @Override
  public String version() {
    return VERSION;
  }
}
