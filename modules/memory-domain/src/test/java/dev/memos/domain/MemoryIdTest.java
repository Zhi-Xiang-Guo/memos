package dev.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MemoryIdTest {
  @Test
  void rejectsBlankIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> new MemoryId(" "));
  }

  @Test
  void retainsIdentifierValue() {
    assertEquals("memory-1", new MemoryId("memory-1").value());
  }
}
