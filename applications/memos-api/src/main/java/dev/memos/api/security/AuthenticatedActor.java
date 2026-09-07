package dev.memos.api.security;

import dev.memos.governance.MemoryScope;
import dev.memos.governance.WriteCapability;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record AuthenticatedActor(MemoryScope scope, String subjectId, Set<String> roles) {
  public AuthenticatedActor {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(subjectId, "subjectId must not be null");
    if (subjectId.isBlank()) {
      throw new IllegalArgumentException("subjectId must not be blank");
    }
    roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public Set<WriteCapability> writeCapabilities() {
    EnumSet<WriteCapability> capabilities = EnumSet.noneOf(WriteCapability.class);
    if (hasRole(MemosRoles.PROJECT_MEMORY_WRITER)) {
      capabilities.add(WriteCapability.WRITE_PROJECT_MEMORY);
    }
    if (hasRole(MemosRoles.PROCEDURAL_MEMORY_WRITER)) {
      capabilities.add(WriteCapability.WRITE_PROCEDURAL_MEMORY);
    }
    return Set.copyOf(capabilities);
  }
}
