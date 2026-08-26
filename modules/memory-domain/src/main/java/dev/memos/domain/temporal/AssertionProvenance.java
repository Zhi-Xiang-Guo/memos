package dev.memos.domain.temporal;

import java.util.Objects;
import java.util.UUID;

public record AssertionProvenance(
    UUID sourceEventId,
    UUID extractionRunId,
    UUID candidateId,
    String extractorVersion,
    String promptVersion,
    String modelVersion,
    String policyVersion,
    String schemaVersion,
    AssertionDerivationRole derivationRole,
    EvidenceSpan evidenceSpan) {
  public AssertionProvenance {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    Objects.requireNonNull(extractionRunId, "extractionRunId must not be null");
    Objects.requireNonNull(candidateId, "candidateId must not be null");
    extractorVersion = TemporalValidation.text(extractorVersion, "extractorVersion", 128);
    promptVersion = TemporalValidation.text(promptVersion, "promptVersion", 128);
    modelVersion = TemporalValidation.text(modelVersion, "modelVersion", 128);
    policyVersion = TemporalValidation.text(policyVersion, "policyVersion", 128);
    schemaVersion = TemporalValidation.text(schemaVersion, "schemaVersion", 128);
    Objects.requireNonNull(derivationRole, "derivationRole must not be null");
  }
}
