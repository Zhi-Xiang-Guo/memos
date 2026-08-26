package dev.memos.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public final class SourceIngestionService {
  private static final String JOB_TYPE = "MATERIALIZE_SOURCE";
  private static final int MAX_CANONICAL_PAYLOAD_BYTES = 65_536;

  private final Clock clock;
  private final IngestionIdentifierGenerator identifierGenerator;
  private final PayloadCanonicalizer payloadCanonicalizer;
  private final SourceIngestionStore store;
  private final IngestionTelemetry telemetry;
  private final String policyVersion;
  private final String modelVersion;
  private final int maxAttempts;

  public SourceIngestionService(
      Clock clock,
      IngestionIdentifierGenerator identifierGenerator,
      PayloadCanonicalizer payloadCanonicalizer,
      SourceIngestionStore store,
      IngestionTelemetry telemetry,
      String policyVersion,
      String modelVersion,
      int maxAttempts) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.identifierGenerator =
        Objects.requireNonNull(identifierGenerator, "identifierGenerator must not be null");
    this.payloadCanonicalizer =
        Objects.requireNonNull(payloadCanonicalizer, "payloadCanonicalizer must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    this.policyVersion = TextValidation.requireText(policyVersion, "policyVersion", 128);
    this.modelVersion = TextValidation.requireText(modelVersion, "modelVersion", 128);
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    this.maxAttempts = maxAttempts;
  }

  public IngestionReceipt ingest(SourceIngestionCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    String canonicalPayload =
        TextValidation.requirePayload(
            payloadCanonicalizer.canonicalize(command.payload()), MAX_CANONICAL_PAYLOAD_BYTES);
    Instant now = clock.instant();
    SourceEventId sourceEventId = identifierGenerator.newSourceEventId();
    MaterializationJobId jobId = identifierGenerator.newMaterializationJobId();
    Sha256Fingerprint contentFingerprint = fingerprint(canonicalPayload);
    Sha256Fingerprint requestFingerprint = requestFingerprint(command, canonicalPayload);

    SourceEvent sourceEvent =
        new SourceEvent(
            sourceEventId,
            command.scope(),
            command.sourceId(),
            command.sessionId(),
            command.idempotencyKey(),
            command.actorType(),
            command.sourceType(),
            command.trustLevel(),
            command.occurredAt(),
            now,
            canonicalPayload,
            contentFingerprint,
            requestFingerprint,
            SourceDeletionState.ACTIVE,
            command.traceId(),
            now);
    MaterializationIntent intent =
        new MaterializationIntent(
            jobId,
            sourceEventId,
            command.scope().tenantId(),
            semanticJobKey(sourceEventId),
            policyVersion,
            modelVersion,
            maxAttempts,
            now,
            command.traceId(),
            now);

    SourceIngestionStore.AcceptanceResult result = store.accept(sourceEvent, intent);
    if (result instanceof SourceIngestionStore.Conflict conflict) {
      telemetry.rejected(conflict.reason());
      throw new IngestionConflictException(conflict.reason());
    }
    SourceIngestionStore.Accepted accepted = (SourceIngestionStore.Accepted) result;
    telemetry.accepted(accepted.disposition());
    return new IngestionReceipt(
        accepted.sourceEventId(),
        accepted.sourceId(),
        accepted.materializationJobId(),
        accepted.disposition(),
        accepted.acceptedAt());
  }

  private String semanticJobKey(SourceEventId sourceEventId) {
    return JOB_TYPE + "/" + sourceEventId + "/" + policyVersion;
  }

  private static Sha256Fingerprint requestFingerprint(
      SourceIngestionCommand command, String canonicalPayload) {
    MessageDigest digest = newDigest();
    add(digest, command.scope().tenantId());
    add(digest, command.scope().userId());
    add(digest, command.scope().agentId());
    add(digest, command.sourceId());
    add(digest, command.sessionId());
    add(digest, command.actorType().name());
    add(digest, command.sourceType().name());
    add(digest, command.trustLevel().name());
    add(digest, command.occurredAt().toString());
    add(digest, canonicalPayload);
    return new Sha256Fingerprint(HexFormat.of().formatHex(digest.digest()));
  }

  private static Sha256Fingerprint fingerprint(String value) {
    MessageDigest digest = newDigest();
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    return new Sha256Fingerprint(HexFormat.of().formatHex(digest.digest()));
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
