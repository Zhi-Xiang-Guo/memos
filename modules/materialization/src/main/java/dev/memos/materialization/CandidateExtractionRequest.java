package dev.memos.materialization;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record CandidateExtractionRequest(
    UUID sourceEventId,
    String content,
    Map<String, String> metadata,
    String promptVersion,
    String schemaVersion) {
  public CandidateExtractionRequest {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    content = MaterializationTextValidation.requireText(content, "content", 65_536);
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    if (metadata.size() > 32) {
      throw new IllegalArgumentException("metadata must contain at most 32 entries");
    }
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      MaterializationTextValidation.requireText(entry.getKey(), "metadata key", 128);
      MaterializationTextValidation.requireText(entry.getValue(), "metadata value", 1_024);
    }
    promptVersion = MaterializationTextValidation.requireText(promptVersion, "promptVersion", 128);
    schemaVersion = MaterializationTextValidation.requireText(schemaVersion, "schemaVersion", 128);
  }
}
