package dev.memos.governance;

import java.util.Objects;

public record MemoryScope(String tenantId, String userId, String agentId) {
  public MemoryScope {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    if (tenantId.isBlank() || userId.isBlank() || agentId.isBlank()) {
      throw new IllegalArgumentException("scope identifiers must not be blank");
    }
  }
}
