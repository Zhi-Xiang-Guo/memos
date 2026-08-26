package dev.memos.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.memos.domain.candidate.BooleanCandidateValue;
import dev.memos.domain.candidate.EvidenceTrust;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.NumberCandidateValue;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.governance.DeterministicCandidateWritePolicy;
import dev.memos.governance.MemoryScope;
import dev.memos.governance.NoveltyAssessment;
import dev.memos.governance.PolicyDecision;
import dev.memos.governance.WriteCapability;
import dev.memos.governance.WritePolicyConfiguration;
import dev.memos.governance.WritePolicyContext;
import dev.memos.governance.WritePolicyOutcome;
import dev.memos.materialization.DecodedCandidateBatch;
import dev.memos.materialization.ProposalDecodingException;
import dev.memos.materialization.StrictCandidateProposalDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WritePolicyFixtureConformanceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final MemoryScope FIXTURE_SCOPE =
      new MemoryScope("fixture-tenant", "fixture-user", "fixture-agent");
  private static final StrictCandidateProposalDecoder DECODER =
      new StrictCandidateProposalDecoder();
  private static final DeterministicCandidateWritePolicy POLICY =
      new DeterministicCandidateWritePolicy(WritePolicyConfiguration.safeDefaults());

  @Test
  void producesByteStablePredictionsMatchingAllVersionOneGoldLabels() throws Exception {
    Path repository = findRepositoryRoot();
    FixtureFile fixture =
        JSON.readValue(
            Files.readString(
                repository.resolve("benchmark/fixtures/write-policy/v1/cases.json"),
                StandardCharsets.UTF_8),
            FixtureFile.class);

    assertThat(fixture.fixture_version()).isEqualTo("write-policy-v1");
    assertThat(fixture.cases()).hasSize(17);

    PredictionRun first = runFixture(fixture);
    PredictionRun second = runFixture(fixture);

    assertThat(second.bytes()).isEqualTo(first.bytes());
    assertThat(first.predictions()).hasSize(17);
    Path output = repository.resolve("target/write-policy/predictions.jsonl");
    Files.createDirectories(output.getParent());
    Files.write(output, first.bytes());
    assertThat(Files.readAllBytes(output)).isEqualTo(first.bytes());

    for (int index = 0; index < fixture.cases().size(); index++) {
      FixtureCase fixtureCase = fixture.cases().get(index);
      assertMatchesGold(
          fixtureCase.case_id(), first.predictions().get(index), fixtureCase.expected());
    }
  }

  private static PredictionRun runFixture(FixtureFile fixture) throws IOException {
    List<Prediction> predictions = new ArrayList<>(fixture.cases().size());
    StringBuilder jsonLines = new StringBuilder();
    for (FixtureCase fixtureCase : fixture.cases()) {
      Prediction prediction = predict(fixtureCase);
      predictions.add(prediction);
      jsonLines.append(JSON.writeValueAsString(prediction)).append('\n');
    }
    return new PredictionRun(
        List.copyOf(predictions), jsonLines.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static Prediction predict(FixtureCase fixtureCase) throws IOException {
    String providerOutput = JSON.writeValueAsString(fixtureCase.provider_output());
    final DecodedCandidateBatch decoded;
    try {
      decoded = DECODER.decode(providerOutput, StrictCandidateProposalDecoder.SCHEMA_VERSION);
    } catch (ProposalDecodingException exception) {
      return new Prediction(fixtureCase.case_id(), "INVALID_SCHEMA", List.of(), List.of(), false);
    }

    List<String> candidateKeys = new ArrayList<>(decoded.candidates().size());
    List<DecisionPrediction> decisions = new ArrayList<>(decoded.candidates().size());
    boolean downstreamIntent = false;
    for (int ordinal = 0; ordinal < decoded.candidates().size(); ordinal++) {
      MemoryCandidateProposal candidate = decoded.candidates().get(ordinal);
      WritePolicyOutcome outcome = POLICY.evaluate(candidate, policyContext(fixtureCase, ordinal));
      candidateKeys.add(candidateKey(candidate, outcome));
      decisions.add(
          new DecisionPrediction(
              ordinal,
              outcome.decision().name(),
              outcome.sensitivityAction().name(),
              outcome.reasons().stream().map(Enum::name).toList()));
      downstreamIntent |= outcome.decision() == PolicyDecision.REMEMBER;
    }
    return new Prediction(
        fixtureCase.case_id(),
        "VALID",
        List.copyOf(candidateKeys),
        List.copyOf(decisions),
        downstreamIntent);
  }

  private static WritePolicyContext policyContext(FixtureCase fixtureCase, int ordinal) {
    FixturePolicyContext fixtureContext = fixtureCase.policy_context();
    String novelty = fixtureContext.novelty_by_ordinal().get(Integer.toString(ordinal));
    if (novelty == null) {
      throw new IllegalArgumentException(
          "missing novelty for " + fixtureCase.case_id() + " candidate " + ordinal);
    }
    Set<WriteCapability> capabilities =
        fixtureContext.capabilities().stream()
            .map(WriteCapability::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return new WritePolicyContext(
        FIXTURE_SCOPE,
        FIXTURE_SCOPE,
        EvidenceTrust.valueOf(fixtureCase.source().trust_level()),
        NoveltyAssessment.valueOf(novelty),
        capabilities,
        fixtureContext.tokenizer_available());
  }

  private static String candidateKey(
      MemoryCandidateProposal candidate, WritePolicyOutcome outcome) {
    StringBuilder key =
        new StringBuilder(candidate.memoryType().name().toLowerCase(Locale.ROOT))
            .append(':')
            .append(candidate.subject().kind().name().toLowerCase(Locale.ROOT));
    if (candidate.subject().label() != null) {
      key.append(':').append(normalize(candidate.subject().label()));
    }
    key.append(':').append(candidate.predicate()).append(':');
    if (outcome.reasons().stream().anyMatch(reason -> reason.name().equals("SECRET_REJECTED"))) {
      return key.append("redacted").toString();
    }
    if (candidate.memoryType() == MemoryType.EPISODIC
        && candidate.eventTime() != null
        && candidate.eventTime().startInclusive() != null) {
      return key.append(candidate.eventTime().startInclusive().atZone(ZoneOffset.UTC).toLocalDate())
          .toString();
    }
    if (candidate.value() instanceof TextCandidateValue text) {
      return key.append(normalize(text.value())).toString();
    }
    if (candidate.value() instanceof BooleanCandidateValue bool) {
      return key.append(bool.value()).toString();
    }
    if (candidate.value() instanceof NumberCandidateValue number) {
      return key.append(number.value().toPlainString()).toString();
    }
    throw new IllegalStateException("unsupported candidate value type");
  }

  private static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  private static void assertMatchesGold(String caseId, Prediction prediction, Expected expected) {
    assertThat(prediction.validation()).as(caseId).isEqualTo(expected.validation());
    assertThat(prediction.candidate_keys())
        .as(caseId)
        .containsExactlyElementsOf(expected.candidate_keys());
    assertThat(prediction.decisions()).as(caseId).containsExactlyElementsOf(expected.decisions());
    assertThat(prediction.downstream_intent()).as(caseId).isEqualTo(expected.downstream_intent());
  }

  private static Path findRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("benchmark/fixtures/write-policy/v1/cases.json"))) {
        return current;
      }
      current = current.getParent();
    }
    return fail("repository root containing the write-policy fixture was not found");
  }

  private record PredictionRun(List<Prediction> predictions, byte[] bytes) {}

  private record Prediction(
      String case_id,
      String validation,
      List<String> candidate_keys,
      List<DecisionPrediction> decisions,
      boolean downstream_intent) {}

  private record DecisionPrediction(
      int ordinal, String decision, String sensitivity_action, List<String> reason_codes) {}

  private record FixtureFile(String fixture_version, List<FixtureCase> cases) {}

  private record FixtureCase(
      String case_id,
      String split,
      List<String> coverage,
      FixtureSource source,
      FixturePolicyContext policy_context,
      Map<String, Object> provider_output,
      Expected expected) {}

  private record FixtureSource(
      String actor_type, String source_type, String trust_level, String content) {}

  private record FixturePolicyContext(
      List<String> capabilities,
      Map<String, String> novelty_by_ordinal,
      boolean tokenizer_available) {}

  private record Expected(
      String validation,
      List<String> candidate_keys,
      List<DecisionPrediction> decisions,
      boolean downstream_intent,
      List<Integer> harmful_if_remembered_ordinals) {}
}
