package dev.memos.materialization;

import java.util.Objects;

public record RawExtractionResponse(String rawJson, ProviderCallMetadata metadata) {
  public RawExtractionResponse {
    Objects.requireNonNull(rawJson, "rawJson must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
  }
}
