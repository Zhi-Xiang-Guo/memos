package dev.memos.adapters.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeterministicEmbeddingAdapterTest {
  @Test
  void returnsStableEmbeddingWithoutExternalProvider() {
    var adapter = new DeterministicEmbeddingAdapter();

    assertEquals(adapter.embed("你好, MemOS"), adapter.embed("你好, MemOS"));
    assertEquals(16, adapter.embed("你好, MemOS").size());
  }
}
