package dev.memos.materialization;

import dev.memos.governance.MemoryScope;

@FunctionalInterface
public interface StorageObservationStore {
  StorageObservation observe(MemoryScope scope);
}
