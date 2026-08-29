package dev.memos.adapters.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.memos.retrieval.EmbeddingRequest;
import org.junit.jupiter.api.Test;

class DeterministicEmbeddingAdapterTest {
  @Test
  void returnsStableEmbeddingWithoutExternalProvider() {
    var adapter = new DeterministicEmbeddingAdapter();

    var request = new EmbeddingRequest("你好, MemOS", DeterministicEmbeddingAdapter.MODEL_VERSION);

    assertEquals(adapter.embed(request), adapter.embed(request));
    assertEquals(64, adapter.embed(request).dimensions());
  }
}
