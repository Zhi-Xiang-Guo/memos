package dev.memos.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.memos.retrieval.EmbeddingRequest;
import dev.memos.retrieval.EmbeddingResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingContextTokenCounterTest {
  @Test
  void usesConfiguredEmbeddingModelTokenCountAndIdentity() {
    var counter =
        new EmbeddingContextTokenCounter(
            request -> result(request, request.modelVersion(), request.text().length()),
            "sha256:model-a");

    assertEquals(new ContextTokenCount(7, 7, 1), counter.count("context"));
    assertEquals("embedding-model:sha256:model-a", counter.version());
  }

  @Test
  void rejectsProviderModelDriftAndInvalidCounts() {
    var drifted =
        new EmbeddingContextTokenCounter(
            request -> result(request, "sha256:model-b", 1), "sha256:model-a");
    var empty =
        new EmbeddingContextTokenCounter(
            request -> result(request, request.modelVersion(), 0), "sha256:model-a");

    assertThrows(IllegalStateException.class, () -> drifted.count("context"));
    assertThrows(IllegalStateException.class, () -> empty.count("context"));
  }

  private static EmbeddingResult result(
      EmbeddingRequest request, String modelVersion, long inputTokens) {
    return new EmbeddingResult(List.of(1.0f), "test", modelVersion, inputTokens);
  }
}
