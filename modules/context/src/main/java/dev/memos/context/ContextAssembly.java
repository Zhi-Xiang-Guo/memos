package dev.memos.context;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ContextAssembly(
    String rendered,
    int tokens,
    int considered,
    int selected,
    boolean truncated,
    String tokenCounterVersion,
    long tokenCountProviderInputTokens,
    int tokenCountProviderCalls,
    List<UUID> selectedVersionIds) {
  public ContextAssembly {
    Objects.requireNonNull(rendered, "rendered must not be null");
    if (tokens < 0 || considered < 0 || selected < 0 || selected > considered) {
      throw new IllegalArgumentException("context counts are invalid");
    }
    Objects.requireNonNull(tokenCounterVersion, "tokenCounterVersion must not be null");
    if (tokenCountProviderInputTokens < 0 || tokenCountProviderCalls < 0) {
      throw new IllegalArgumentException("context token provider usage is invalid");
    }
    if (tokenCountProviderCalls == 0 && tokenCountProviderInputTokens != 0) {
      throw new IllegalArgumentException("local context token counts cannot report provider input");
    }
    selectedVersionIds =
        List.copyOf(
            Objects.requireNonNull(selectedVersionIds, "selectedVersionIds must not be null"));
  }
}
