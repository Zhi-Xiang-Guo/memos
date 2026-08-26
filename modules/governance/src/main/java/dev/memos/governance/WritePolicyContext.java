package dev.memos.governance;

import dev.memos.domain.candidate.EvidenceTrust;
import java.util.Objects;
import java.util.Set;

public record WritePolicyContext(
    MemoryScope sourceScope,
    MemoryScope targetScope,
    EvidenceTrust sourceTrust,
    NoveltyAssessment novelty,
    Set<WriteCapability> capabilities,
    boolean tokenizerAvailable) {
  public WritePolicyContext {
    Objects.requireNonNull(sourceScope, "sourceScope must not be null");
    Objects.requireNonNull(targetScope, "targetScope must not be null");
    Objects.requireNonNull(sourceTrust, "sourceTrust must not be null");
    Objects.requireNonNull(novelty, "novelty must not be null");
    capabilities =
        Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
  }

  public boolean hasCapability(WriteCapability capability) {
    return capabilities.contains(capability);
  }
}
