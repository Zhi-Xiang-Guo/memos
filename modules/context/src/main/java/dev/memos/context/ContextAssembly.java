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
    List<UUID> selectedVersionIds) {
  public ContextAssembly {
    Objects.requireNonNull(rendered, "rendered must not be null");
    if (tokens < 0 || considered < 0 || selected < 0 || selected > considered) {
      throw new IllegalArgumentException("context counts are invalid");
    }
    Objects.requireNonNull(tokenCounterVersion, "tokenCounterVersion must not be null");
    selectedVersionIds =
        List.copyOf(
            Objects.requireNonNull(selectedVersionIds, "selectedVersionIds must not be null"));
  }
}
