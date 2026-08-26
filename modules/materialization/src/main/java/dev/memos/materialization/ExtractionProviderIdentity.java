package dev.memos.materialization;

public record ExtractionProviderIdentity(
    String provider, String modelVersion, String promptVersion, String schemaVersion) {
  public ExtractionProviderIdentity {
    provider = MaterializationTextValidation.requireText(provider, "provider", 128);
    modelVersion = MaterializationTextValidation.requireText(modelVersion, "modelVersion", 128);
    promptVersion = MaterializationTextValidation.requireText(promptVersion, "promptVersion", 128);
    schemaVersion = MaterializationTextValidation.requireText(schemaVersion, "schemaVersion", 128);
  }
}
