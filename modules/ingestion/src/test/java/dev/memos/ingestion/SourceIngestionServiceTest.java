package dev.memos.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.memos.governance.MemoryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceIngestionServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  @Test
  void createsSourceAndMaterializationIntentWithStableFingerprints() {
    CapturingStore store = new CapturingStore();
    SourceIngestionService service = service(store);

    IngestionReceipt receipt = service.ingest(command("idempotency-1", "source-1", " {\"a\":1} "));

    assertEquals(IngestionDisposition.ACCEPTED, receipt.disposition());
    assertFalse(receipt.replayed());
    assertEquals(NOW, receipt.acceptedAt());
    assertEquals(1, store.events.size());
    assertEquals("{\"a\":1}", store.events.getFirst().canonicalPayload());
    assertEquals(64, store.events.getFirst().contentFingerprint().hex().length());
    assertEquals(
        "MATERIALIZE_SOURCE/" + receipt.sourceEventId() + "/ingestion-v1",
        store.intents.getFirst().semanticJobKey());
    assertEquals(5, store.intents.getFirst().maxAttempts());
  }

  @Test
  void idempotencyKeyIsExcludedFromImmutableRequestFingerprint() {
    CapturingStore store = new CapturingStore();
    SourceIngestionService service = service(store);

    service.ingest(command("idempotency-1", "source-1", "{\"a\":1}"));
    service.ingest(command("idempotency-2", "source-1", "{\"a\":1}"));

    assertEquals(
        store.events.get(0).requestFingerprint(), store.events.get(1).requestFingerprint());
    assertNotEquals(store.events.get(0).sourceEventId(), store.events.get(1).sourceEventId());
  }

  @Test
  void mapsStoreConflictToTypedExceptionAndTelemetry() {
    CapturingStore store = new CapturingStore();
    store.conflict = IngestionConflict.IDEMPOTENCY_KEY_REUSED;
    CapturingTelemetry telemetry = new CapturingTelemetry();
    SourceIngestionService service = service(store, telemetry);

    IngestionConflictException exception =
        assertThrows(
            IngestionConflictException.class,
            () -> service.ingest(command("idempotency-1", "source-1", "{}")));

    assertEquals(IngestionConflict.IDEMPOTENCY_KEY_REUSED, exception.reason());
    assertSame(exception.reason(), telemetry.rejected);
  }

  @Test
  void preservesStoredReceiptForReplay() {
    CapturingStore store = new CapturingStore();
    store.disposition = IngestionDisposition.SOURCE_REPLAY;
    SourceIngestionService service = service(store);

    IngestionReceipt receipt = service.ingest(command("idempotency-2", "source-1", "{}"));

    assertTrue(receipt.replayed());
    assertEquals(IngestionDisposition.SOURCE_REPLAY, receipt.disposition());
  }

  @Test
  void rejectsBlankIdentifiersBeforeCallingStore() {
    CapturingStore store = new CapturingStore();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceIngestionCommand(
                new MemoryScope("tenant", "user", "agent"),
                " ",
                "session",
                "idempotency",
                ActorType.USER,
                SourceType.CONVERSATION_MESSAGE,
                TrustLevel.DIRECT_USER,
                NOW,
                "{}",
                "trace"));
    assertTrue(store.events.isEmpty());
  }

  private static SourceIngestionService service(CapturingStore store) {
    return service(store, IngestionTelemetry.NOOP);
  }

  private static SourceIngestionService service(
      CapturingStore store, IngestionTelemetry telemetry) {
    AtomicInteger sequence = new AtomicInteger();
    IngestionIdentifierGenerator generator =
        new IngestionIdentifierGenerator() {
          @Override
          public SourceEventId newSourceEventId() {
            return new SourceEventId(new UUID(0, sequence.incrementAndGet()));
          }

          @Override
          public MaterializationJobId newMaterializationJobId() {
            return new MaterializationJobId(new UUID(1, sequence.incrementAndGet()));
          }
        };
    return new SourceIngestionService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        generator,
        String::trim,
        store,
        telemetry,
        "ingestion-v1",
        "deterministic-fake-v1",
        5);
  }

  private static SourceIngestionCommand command(
      String idempotencyKey, String sourceId, String payload) {
    return new SourceIngestionCommand(
        new MemoryScope("tenant-1", "user-1", "agent-1"),
        sourceId,
        "session-1",
        idempotencyKey,
        ActorType.USER,
        SourceType.CONVERSATION_MESSAGE,
        TrustLevel.DIRECT_USER,
        Instant.parse("2026-08-26T23:59:00Z"),
        payload,
        "trace-1");
  }

  private static final class CapturingStore implements SourceIngestionStore {
    private final List<SourceEvent> events = new ArrayList<>();
    private final List<MaterializationIntent> intents = new ArrayList<>();
    private IngestionDisposition disposition = IngestionDisposition.ACCEPTED;
    private IngestionConflict conflict;

    @Override
    public AcceptanceResult accept(SourceEvent sourceEvent, MaterializationIntent intent) {
      events.add(sourceEvent);
      intents.add(intent);
      if (conflict != null) {
        return new Conflict(conflict);
      }
      return new Accepted(
          sourceEvent.sourceEventId(),
          sourceEvent.sourceId(),
          intent.jobId(),
          disposition,
          sourceEvent.receivedAt());
    }
  }

  private static final class CapturingTelemetry implements IngestionTelemetry {
    private IngestionConflict rejected;

    @Override
    public void rejected(IngestionConflict conflict) {
      rejected = conflict;
    }
  }
}
