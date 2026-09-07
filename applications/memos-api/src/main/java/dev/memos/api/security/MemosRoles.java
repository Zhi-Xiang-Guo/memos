package dev.memos.api.security;

import java.util.Set;

public final class MemosRoles {
  public static final String USER = "USER";
  public static final String OPERATOR = "OPERATOR";
  public static final String PRIVACY_ADMIN = "PRIVACY_ADMIN";
  public static final String PROJECT_MEMORY_WRITER = "PROJECT_MEMORY_WRITER";
  public static final String PROCEDURAL_MEMORY_WRITER = "PROCEDURAL_MEMORY_WRITER";
  public static final Set<String> ALLOWED =
      Set.of(USER, OPERATOR, PRIVACY_ADMIN, PROJECT_MEMORY_WRITER, PROCEDURAL_MEMORY_WRITER);

  private MemosRoles() {}
}
