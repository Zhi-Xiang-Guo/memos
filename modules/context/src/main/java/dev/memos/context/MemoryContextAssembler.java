package dev.memos.context;

import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.retrieval.RankedMemory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Selects diverse provenance-bearing evidence and renders it as explicitly untrusted data. */
public final class MemoryContextAssembler {
  private static final String OPEN = "<memory-evidence trust=\"untrusted-data\">\n";
  private static final String CLOSE = "</memory-evidence>";

  private final ContextTokenCounter counter;

  public MemoryContextAssembler(ContextTokenCounter counter) {
    this.counter = Objects.requireNonNull(counter, "counter must not be null");
  }

  public ContextAssembly assemble(List<RankedMemory> ranked, ContextBudget budget) {
    ranked = List.copyOf(Objects.requireNonNull(ranked, "ranked must not be null"));
    Objects.requireNonNull(budget, "budget must not be null");
    StringBuilder rendered = new StringBuilder(OPEN);
    ContextTokenCount baseCount = counter.count(OPEN + CLOSE);
    int baseTokens = baseCount.tokens();
    if (baseTokens < 1 || baseTokens > budget.maxTokens()) {
      throw new IllegalArgumentException("maxTokens cannot contain the context envelope");
    }
    int tokens = baseTokens;
    long providerInputTokens = baseCount.providerInputTokens();
    int providerCalls = baseCount.providerCalls();
    Map<UUID, Integer> perLineage = new HashMap<>();
    List<UUID> selected = new ArrayList<>();
    boolean truncated = false;

    for (RankedMemory rankedMemory : ranked) {
      int lineageCount = perLineage.getOrDefault(rankedMemory.memory().memoryId(), 0);
      int lineageLimit = rankedMemory.memory().status() == AssertionStatus.CONFLICTED ? 2 : 1;
      if (lineageCount >= lineageLimit) {
        continue;
      }
      String item = renderItem(rankedMemory);
      ContextTokenCount tentativeCount = counter.count(rendered + item + CLOSE);
      providerInputTokens =
          Math.addExact(providerInputTokens, tentativeCount.providerInputTokens());
      providerCalls = Math.addExact(providerCalls, tentativeCount.providerCalls());
      if (tentativeCount.tokens() > budget.maxTokens()) {
        truncated = true;
        continue;
      }
      rendered.append(item);
      tokens = tentativeCount.tokens();
      perLineage.put(rankedMemory.memory().memoryId(), lineageCount + 1);
      selected.add(rankedMemory.memory().versionId());
    }
    rendered.append(CLOSE);
    return new ContextAssembly(
        rendered.toString(),
        tokens,
        ranked.size(),
        selected.size(),
        truncated,
        counter.version(),
        providerInputTokens,
        providerCalls,
        selected);
  }

  private static String renderItem(RankedMemory ranked) {
    var memory = ranked.memory();
    String sources =
        memory.sourceEventIds().stream()
            .map(UUID::toString)
            .sorted()
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return "  <memory id=\""
        + memory.memoryId()
        + "\" version-id=\""
        + memory.versionId()
        + "\" status=\""
        + memory.status()
        + "\" predicate=\""
        + escape(memory.predicate())
        + "\" source-event-ids=\""
        + sources
        + "\">"
        + escape(memory.normalizedContent())
        + "</memory>\n";
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
