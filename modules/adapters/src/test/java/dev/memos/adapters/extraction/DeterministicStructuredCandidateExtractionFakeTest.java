package dev.memos.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.materialization.CandidateExtractionRequest;
import dev.memos.materialization.RawExtractionResponse;
import dev.memos.materialization.StrictCandidateProposalDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DeterministicStructuredCandidateExtractionFakeTest {
  private static final UUID SOURCE_ID = UUID.fromString("019c36de-8938-7000-8000-000000000001");

  @Test
  void returnsByteStablePreferenceForSameInputAndVersions() {
    DeterministicStructuredCandidateExtractionFake fake =
        new DeterministicStructuredCandidateExtractionFake();
    CandidateExtractionRequest request =
        request(DeterministicStructuredCandidateExtractionFake.DURABLE_PREFERENCE_SOURCE);

    RawExtractionResponse first = fake.extract(request);
    RawExtractionResponse second = fake.extract(request);

    assertThat(second).isEqualTo(first);
    assertThat(first.rawJson()).contains("\"schema_version\":\"memory-candidate.v1\"");
    assertThat(first.rawJson()).contains("\"predicate\":\"preference.editor.theme\"");
    assertThat(first.metadata().provider()).isEqualTo("fake");
    assertThat(first.metadata().modelVersion()).isEqualTo("deterministic-fixture-v1");
    assertThat(first.metadata().tokenUsage().totalTokens()).isZero();
    assertThat(first.metadata().latency()).isZero();
    assertThat(
            new StrictCandidateProposalDecoder()
                .decode(first.rawJson(), "memory-candidate.v1")
                .candidates())
        .hasSize(1);
  }

  @Test
  void unknownInputFailsSafeToEmptyCandidates() {
    RawExtractionResponse response =
        new DeterministicStructuredCandidateExtractionFake()
            .extract(request("This input has no deterministic script."));

    assertThat(response.rawJson())
        .isEqualTo("{\"schema_version\":\"memory-candidate.v1\",\"candidates\":[]}");
  }

  @Test
  void acceptsFixtureDrivenScriptWithoutInterpretingItsPolicy() throws IOException {
    String scriptedResponse = resource("/extraction/scripted-project-response.json");
    DeterministicStructuredCandidateExtractionFake fake =
        new DeterministicStructuredCandidateExtractionFake(
            "deterministic-fixture-v1",
            "candidate-extraction-v1",
            "memory-candidate.v1",
            Map.of("scripted project case", scriptedResponse));

    RawExtractionResponse response = fake.extract(request("scripted project case"));

    assertThat(response.rawJson()).isEqualTo(scriptedResponse);
    assertThat(
            new StrictCandidateProposalDecoder()
                .decode(response.rawJson(), "memory-candidate.v1")
                .candidates())
        .hasSize(1);
  }

  @Test
  void refusesInvocationInsideAnActiveSpringTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  new DeterministicStructuredCandidateExtractionFake()
                      .extract(request("This must not be called.")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("structured extraction must run outside a database transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void rejectsVersionDrift() {
    DeterministicStructuredCandidateExtractionFake fake =
        new DeterministicStructuredCandidateExtractionFake();
    CandidateExtractionRequest request =
        new CandidateExtractionRequest(
            SOURCE_ID, "content", Map.of(), "candidate-extraction-v2", "memory-candidate.v1");

    assertThatThrownBy(() -> fake.extract(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prompt version");
  }

  private static CandidateExtractionRequest request(String content) {
    return new CandidateExtractionRequest(
        SOURCE_ID,
        content,
        Map.of("source_type", "CONVERSATION_MESSAGE"),
        "candidate-extraction-v1",
        "memory-candidate.v1");
  }

  private static String resource(String path) throws IOException {
    try (InputStream stream =
        DeterministicStructuredCandidateExtractionFakeTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IOException("missing test resource");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
  }
}
