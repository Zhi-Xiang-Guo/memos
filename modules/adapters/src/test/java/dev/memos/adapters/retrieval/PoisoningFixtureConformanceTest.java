package dev.memos.adapters.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.memos.context.CodePointTokenCounter;
import dev.memos.context.ContextBudget;
import dev.memos.context.MemoryContextAssembler;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.retrieval.CandidateSource;
import dev.memos.retrieval.ComponentSignal;
import dev.memos.retrieval.ProjectedMemory;
import dev.memos.retrieval.RankedMemory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import tools.jackson.databind.json.JsonMapper;

class PoisoningFixtureConformanceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-08-30T02:00:00Z");

  @Test
  void keepsEveryAdversarialMemoryAsTextInsideOneUntrustedBoundary() throws Exception {
    Path repository = findRepositoryRoot();
    FixtureFile fixture =
        JSON.readValue(
            Files.readString(
                repository.resolve("benchmark/fixtures/poisoning/v1/cases.json"),
                StandardCharsets.UTF_8),
            FixtureFile.class);

    assertThat(fixture.fixture_version()).isEqualTo("memory-poisoning-boundary-v1");
    assertThat(fixture.cases()).hasSize(4);
    var assembler = new MemoryContextAssembler(new CodePointTokenCounter());
    for (FixtureCase fixtureCase : fixture.cases()) {
      String rendered =
          assembler.assemble(List.of(memory(fixtureCase)), new ContextBudget(4_000)).rendered();
      var document =
          secureFactory()
              .newDocumentBuilder()
              .parse(new InputSource(new java.io.StringReader(rendered)));

      assertThat(document.getDocumentElement().getTagName()).isEqualTo("memory-evidence");
      assertThat(document.getDocumentElement().getAttribute("trust")).isEqualTo("untrusted-data");
      assertThat(document.getElementsByTagName("memory").getLength()).isEqualTo(1);
      assertThat(document.getElementsByTagName("system").getLength()).isZero();
      assertThat(document.getElementsByTagName("tool").getLength()).isZero();
      assertThat(document.getElementsByTagName("memory").item(0).getTextContent())
          .isEqualTo(fixtureCase.content());
    }
  }

  private static DocumentBuilderFactory secureFactory() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setExpandEntityReferences(false);
    return factory;
  }

  private static RankedMemory memory(FixtureCase fixtureCase) {
    UUID memoryId = stableUuid("memory:" + fixtureCase.case_id());
    UUID versionId = stableUuid("version:" + fixtureCase.case_id());
    return new RankedMemory(
        new ProjectedMemory(
            memoryId,
            versionId,
            MemoryType.SEMANTIC,
            SubjectKind.USER,
            null,
            fixtureCase.predicate(),
            AssertionStatus.CURRENT,
            fixtureCase.content(),
            null,
            null,
            NOW,
            List.of(stableUuid("source:" + fixtureCase.case_id())),
            "projection-v1",
            "fixture-embedding-v1",
            1,
            NOW),
        1.0,
        List.of(new ComponentSignal(CandidateSource.VECTOR, 1, 1.0)),
        null);
  }

  private static UUID stableUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Path findRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("mvnw"))
          && Files.isDirectory(current.resolve("benchmark/fixtures"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }

  private record FixtureFile(String fixture_version, List<FixtureCase> cases) {}

  private record FixtureCase(String case_id, String content, String predicate) {}
}
