package dev.memos.adapters.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.memos.materialization.JobFailureKind;
import dev.memos.materialization.ProjectionEmbeddingProviderException;
import dev.memos.materialization.ProjectionEmbeddingRequest;
import dev.memos.retrieval.EmbeddingRequest;
import org.junit.jupiter.api.Test;

class DeterministicEmbeddingAdapterTest {
  @Test
  void returnsStableEmbeddingWithoutExternalProvider() {
    var adapter = new DeterministicEmbeddingAdapter();

    var request = new EmbeddingRequest("你好, MemOS", DeterministicEmbeddingAdapter.MODEL_VERSION);

    assertEquals(adapter.embed(request), adapter.embed(request));
    assertEquals(1_024, adapter.embed(request).dimensions());
  }

  @Test
  void rejectsRetiredProjectionModelWithoutRetrying() {
    var adapter = new DeterministicEmbeddingAdapter();

    ProjectionEmbeddingProviderException exception =
        assertThrows(
            ProjectionEmbeddingProviderException.class,
            () -> adapter.embed(new ProjectionEmbeddingRequest("memory", "retired-model")));

    assertEquals(JobFailureKind.PERMANENT, exception.kind());
    assertEquals("EMBEDDING_MODEL_VERSION_MISMATCH", exception.errorClass().value());
  }
}
