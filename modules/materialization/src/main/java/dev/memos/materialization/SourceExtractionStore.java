package dev.memos.materialization;

import dev.memos.governance.MemoryScope;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface SourceExtractionStore {
  Optional<SourceForExtraction> find(MemoryScope scope, UUID sourceEventId);
}
