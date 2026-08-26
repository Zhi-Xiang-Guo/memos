package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.TransitionOperation;
import dev.memos.governance.MemoryScope;
import dev.memos.materialization.CorrectionSelection;
import dev.memos.materialization.InvalidationSelection;
import dev.memos.materialization.TemporalMemoryMutation;
import dev.memos.materialization.TemporalMutationDisposition;
import dev.memos.materialization.TemporalMutationResult;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

class TemporalMemoryMutationControllerTest {
  private static final UUID LINEAGE_ID = uuid(1);
  private static final UUID VERSION_ID = uuid(2);
  private static final UUID SOURCE_ID = uuid(3);
  private static final UUID CANDIDATE_ID = uuid(4);
  private static final UUID TRANSITION_ID = uuid(5);
  private static final Instant NOW = Instant.parse("2026-08-27T01:00:00Z");

  private FakeMutations mutations;
  private TemporalMemoryMutationController controller;

  @BeforeEach
  void setUp() {
    MDC.put(TraceIdFilter.TRACE_ID_KEY, "trace-feature-3");
    mutations = new FakeMutations(result(TemporalMutationDisposition.APPLIED));
    controller =
        new TemporalMemoryMutationController(
            mutations,
            ignored -> new MemoryScope("tenant-a", "user-a", "agent-a"),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void correctionSelectsExistingScopedEvidenceAndParsesStrongEtag() {
    var response =
        controller.correct(
            LINEAGE_ID,
            "correction-key",
            "\"7\"",
            new TemporalMemoryCorrectionRequest(
                VERSION_ID, SOURCE_ID, CANDIDATE_ID, "USER_CORRECTION"),
            new MockHttpServletRequest());

    assertThat(mutations.correction.scope().tenantId()).isEqualTo("tenant-a");
    assertThat(mutations.correction.lineageId().value()).isEqualTo(LINEAGE_ID);
    assertThat(mutations.correction.incorrectVersionId().value()).isEqualTo(VERSION_ID);
    assertThat(mutations.correction.sourceEventId()).isEqualTo(SOURCE_ID);
    assertThat(mutations.correction.candidateId()).isEqualTo(CANDIDATE_ID);
    assertThat(mutations.correction.expectedLockVersion()).isEqualTo(7);
    assertThat(mutations.correction.traceId()).isEqualTo("trace-feature-3");
    assertThat(mutations.correction.requestedAt()).isEqualTo(NOW);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"8\"");
    assertThat(response.getBody().disposition()).isEqualTo("APPLIED");
    assertThat(response.getBody().affectedVersionIds()).containsExactly(VERSION_ID.toString());
    assertThat(response.getBody().transitionIds()).containsExactly(TRANSITION_ID.toString());
  }

  @Test
  void invalidationDoesNotAcceptClientSuppliedProviderMetadata() {
    controller.invalidate(
        LINEAGE_ID,
        "invalidate-key",
        "\"7\"",
        new TemporalMemoryInvalidationRequest(VERSION_ID, SOURCE_ID, "USER_DISPUTED"),
        new MockHttpServletRequest());

    assertThat(mutations.invalidation.versionId().value()).isEqualTo(VERSION_ID);
    assertThat(mutations.invalidation.sourceEventId()).isEqualTo(SOURCE_ID);
    assertThat(mutations.invalidation.reason()).isEqualTo("USER_DISPUTED");
  }

  @Test
  void replayReturnsOriginalContentFreeReceiptAndEtag() {
    mutations.result = result(TemporalMutationDisposition.REPLAYED);

    var response =
        controller.invalidate(
            LINEAGE_ID,
            "invalidate-key",
            "\"7\"",
            new TemporalMemoryInvalidationRequest(VERSION_ID, SOURCE_ID, "USER_DISPUTED"),
            new MockHttpServletRequest());

    assertThat(response.getBody().disposition()).isEqualTo("REPLAYED");
    assertThat(response.getBody().operation()).isEqualTo("INVALIDATE");
    assertThat(response.getBody().lockVersion()).isEqualTo(8);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"8\"");
  }

  @Test
  void wildcardWeakMultipleMalformedAndOverflowEtagsFailBeforeMutation() {
    for (String invalid : List.of("*", "W/\"7\"", "7", "\"7\", \"8\"", "\"9223372036854775808\"")) {
      assertThatThrownBy(
              () ->
                  controller.invalidate(
                      LINEAGE_ID,
                      "invalidate-key",
                      invalid,
                      new TemporalMemoryInvalidationRequest(VERSION_ID, SOURCE_ID, "USER_DISPUTED"),
                      new MockHttpServletRequest()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("If-Match");
    }
    assertThat(mutations.invalidation).isNull();
  }

  @Test
  void reasonMustBeAContentFreeUppercaseCode() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var violations =
          factory
              .getValidator()
              .validate(
                  new TemporalMemoryCorrectionRequest(
                      VERSION_ID, SOURCE_ID, CANDIDATE_ID, "contains private content"));

      assertThat(violations)
          .singleElement()
          .satisfies(
              violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("reason"));
    }
  }

  private static TemporalMutationResult result(TemporalMutationDisposition disposition) {
    return new TemporalMutationResult(
        disposition,
        TransitionOperation.INVALIDATE,
        new MemoryLineageId(LINEAGE_ID),
        8,
        List.of(new AssertionVersionId(VERSION_ID)),
        List.of(new StateTransitionId(TRANSITION_ID)));
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }

  private static final class FakeMutations implements TemporalMemoryMutation {
    private TemporalMutationResult result;
    private CorrectionSelection correction;
    private InvalidationSelection invalidation;

    private FakeMutations(TemporalMutationResult result) {
      this.result = result;
    }

    @Override
    public TemporalMutationResult correct(CorrectionSelection selection) {
      correction = selection;
      return result;
    }

    @Override
    public TemporalMutationResult invalidate(InvalidationSelection selection) {
      invalidation = selection;
      return result;
    }
  }
}
