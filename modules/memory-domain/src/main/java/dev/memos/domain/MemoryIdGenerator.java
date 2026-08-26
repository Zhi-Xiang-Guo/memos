package dev.memos.domain;

@FunctionalInterface
public interface MemoryIdGenerator {
  MemoryId nextId();
}
