package dev.memos.api.http;

import dev.memos.governance.MemoryScope;
import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface ScopeContextResolver {
  MemoryScope resolve(HttpServletRequest request);
}
