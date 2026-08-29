package dev.memos.adapters.system;

import dev.memos.governance.DeletionIdGenerator;
import java.util.UUID;

public final class UuidDeletionIdGenerator implements DeletionIdGenerator {
  @Override
  public UUID newOperationId() {
    return UUID.randomUUID();
  }
}
