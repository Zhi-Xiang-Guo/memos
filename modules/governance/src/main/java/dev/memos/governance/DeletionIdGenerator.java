package dev.memos.governance;

import java.util.UUID;

@FunctionalInterface
public interface DeletionIdGenerator {
  UUID newOperationId();
}
