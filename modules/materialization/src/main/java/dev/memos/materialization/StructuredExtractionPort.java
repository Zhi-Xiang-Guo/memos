package dev.memos.materialization;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface StructuredExtractionPort {
  ExtractionResult extract(ExtractionRequest request);

  record ExtractionRequest(String sourceEventId, String content, Map<String, String> metadata) {
    public ExtractionRequest {
      Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
      Objects.requireNonNull(content, "content must not be null");
      metadata = Map.copyOf(metadata);
    }
  }

  record ExtractionResult(List<String> candidates, String provider, String modelVersion) {
    public ExtractionResult {
      candidates = List.copyOf(candidates);
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    }
  }
}
