package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.memos.governance.MemoryScope;
import dev.memos.materialization.StorageObservation;
import dev.memos.materialization.StorageObservationStore;
import dev.memos.materialization.StorageRelationObservation;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StorageObservationControllerTest {
  private static final MemoryScope SCOPE = new MemoryScope("tenant-a", "user-a", "agent-a");

  @Test
  void returnsOnlyContentFreeMeasurementsForTheAuthenticatedScope() {
    AtomicReference<MemoryScope> observedScope = new AtomicReference<>();
    StorageObservationStore store =
        scope -> {
          observedScope.set(scope);
          return new StorageObservation(
              List.of(
                  new StorageRelationObservation("memos.outbox_job", 2, 240),
                  new StorageRelationObservation("memos.source_event", 1, 160)),
              3,
              400,
              16_384,
              8_192,
              24_576);
        };
    var controller = new StorageObservationController(store, ignored -> SCOPE);

    StorageObservationResponse response = controller.observe(new MockHttpServletRequest());

    assertThat(observedScope.get()).isEqualTo(SCOPE);
    assertThat(response.schemaVersion()).isEqualTo("memos-storage-observation.v1");
    assertThat(response.scope().rowCount()).isEqualTo(3);
    assertThat(response.scope().rowBytes()).isEqualTo(400);
    assertThat(response.scope().relations())
        .extracting(StorageObservationResponse.RelationResponse::relation)
        .containsExactly("memos.outbox_job", "memos.source_event");
    assertThat(response.database().tableBytes()).isEqualTo(16_384);
    assertThat(response.database().indexBytes()).isEqualTo(8_192);
    assertThat(response.database().totalBytes()).isEqualTo(24_576);
  }
}
