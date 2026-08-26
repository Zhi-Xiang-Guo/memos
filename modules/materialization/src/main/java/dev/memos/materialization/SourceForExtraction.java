package dev.memos.materialization;

import dev.memos.domain.candidate.EvidenceTrust;
import dev.memos.governance.MemoryScope;
import dev.memos.governance.NoveltyAssessment;
import dev.memos.governance.WriteCapability;
import dev.memos.governance.WritePolicyContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record SourceForExtraction(
    UUID sourceEventId,
    MemoryScope sourceScope,
    MemoryScope targetScope,
    ExtractionActorType actorType,
    ExtractionSourceType sourceType,
    EvidenceTrust sourceTrust,
    SourceContentState contentState,
    String content,
    Map<String, String> metadata,
    Map<Integer, NoveltyAssessment> noveltyByOrdinal,
    Set<WriteCapability> capabilities,
    boolean tokenizerAvailable) {
  public SourceForExtraction {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    Objects.requireNonNull(sourceScope, "sourceScope must not be null");
    Objects.requireNonNull(targetScope, "targetScope must not be null");
    Objects.requireNonNull(actorType, "actorType must not be null");
    Objects.requireNonNull(sourceType, "sourceType must not be null");
    Objects.requireNonNull(sourceTrust, "sourceTrust must not be null");
    Objects.requireNonNull(contentState, "contentState must not be null");
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    noveltyByOrdinal =
        Map.copyOf(Objects.requireNonNull(noveltyByOrdinal, "noveltyByOrdinal must not be null"));
    capabilities =
        Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
    if (contentState == SourceContentState.ACTIVE) {
      content = MaterializationTextValidation.requireText(content, "content", 65_536);
    } else if (content != null || !metadata.isEmpty()) {
      throw new IllegalArgumentException("ERASED sources must not expose content or metadata");
    }
    for (Integer ordinal : noveltyByOrdinal.keySet()) {
      if (ordinal == null || ordinal < 0) {
        throw new IllegalArgumentException("novelty ordinals must not be negative");
      }
    }
  }

  public WritePolicyContext policyContext(int ordinal) {
    NoveltyAssessment novelty = noveltyByOrdinal.getOrDefault(ordinal, NoveltyAssessment.NEW);
    return new WritePolicyContext(
        sourceScope, targetScope, sourceTrust, novelty, capabilities, tokenizerAvailable);
  }
}
