package dev.memos.adapters.fake;

import dev.memos.materialization.StructuredExtractionPort;
import java.util.List;

public final class FakeStructuredExtractionAdapter implements StructuredExtractionPort {
  @Override
  public ExtractionResult extract(ExtractionRequest request) {
    if (request.content().isBlank()) {
      return new ExtractionResult(List.of(), "fake", "deterministic-v1");
    }
    return new ExtractionResult(List.of(request.content().strip()), "fake", "deterministic-v1");
  }
}
