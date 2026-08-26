package dev.memos.api.http;

import dev.memos.governance.MemoryScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class TrustedHeaderScopeContextResolver implements ScopeContextResolver {
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String USER_HEADER = "X-User-Id";
  static final String AGENT_HEADER = "X-Agent-Id";

  @Override
  public MemoryScope resolve(HttpServletRequest request) {
    return new MemoryScope(
        requiredHeader(request, TENANT_HEADER),
        requiredHeader(request, USER_HEADER),
        requiredHeader(request, AGENT_HEADER));
  }

  private static String requiredHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " header is required");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(name + " header must not exceed 128 characters");
    }
    return value;
  }
}
