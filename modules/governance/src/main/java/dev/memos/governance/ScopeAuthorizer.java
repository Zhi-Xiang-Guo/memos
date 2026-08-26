package dev.memos.governance;

@FunctionalInterface
public interface ScopeAuthorizer {
  boolean mayAccess(MemoryScope actor, MemoryScope target);
}
