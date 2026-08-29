package dev.memos.api.security;

import dev.memos.governance.MemoryScope;
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
}
