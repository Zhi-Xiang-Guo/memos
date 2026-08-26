package dev.memos.materialization;

import java.time.Duration;
import java.util.Objects;

public record ProviderCallMetadata(
    String provider,
    String modelVersion,
    String promptVersion,
    String schemaVersion,
    String providerCallId,
    ProviderTokenUsage tokenUsage,
    Duration latency) {
  public ProviderCallMetadata {
    provider = MaterializationTextValidation.requireText(provider, "provider", 128);
    modelVersion = MaterializationTextValidation.requireText(modelVersion, "modelVersion", 128);
    promptVersion = MaterializationTextValidation.requireText(promptVersion, "promptVersion", 128);
    schemaVersion = MaterializationTextValidation.requireText(schemaVersion, "schemaVersion", 128);
    providerCallId =
        MaterializationTextValidation.requireText(providerCallId, "providerCallId", 200);
    Objects.requireNonNull(tokenUsage, "tokenUsage must not be null");
    Objects.requireNonNull(latency, "latency must not be null");
    if (latency.isNegative()) {
      throw new IllegalArgumentException("latency must not be negative");
    }
  }
}
