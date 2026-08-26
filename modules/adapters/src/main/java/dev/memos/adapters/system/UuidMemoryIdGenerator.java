package dev.memos.adapters.system;

import dev.memos.domain.MemoryId;
import dev.memos.domain.MemoryIdGenerator;
import java.util.UUID;

public final class UuidMemoryIdGenerator implements MemoryIdGenerator {
  @Override
  public MemoryId nextId() {
    return new MemoryId(UUID.randomUUID().toString());
  }
}
