package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.memos.domain.candidate.BooleanCandidateValue;
import dev.memos.domain.candidate.CandidateRelationType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TemporalPrecision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StrictCandidateProposalDecoderTest {
  private final StrictCandidateProposalDecoder decoder = new StrictCandidateProposalDecoder();

  @Test
  void decodesFixtureCompatibleSnakeCaseProposal() {
    DecodedCandidateBatch batch =
        decoder.decode(validJson(), StrictCandidateProposalDecoder.SCHEMA_VERSION);

    assertEquals("memory-candidate.v1", batch.schemaVersion());
    assertEquals(1, batch.candidates().size());
    var candidate = batch.candidates().getFirst();
    assertEquals(SubjectKind.AGENT, candidate.subject().kind());
    assertInstanceOf(BooleanCandidateValue.class, candidate.value());
    assertEquals(TemporalPrecision.DAY, candidate.eventTime().precision());
    assertEquals(
        CandidateRelationType.REINFORCES, candidate.candidateRelations().getFirst().type());
  }

  @Test
  void rejectsUnknownScopeFieldFromModel() {
    String invalid =
        "{\"schema_version\":\"memory-candidate.v1\",\"tenant_id\":\"evil\",\"candidates\":[]}";

    ProposalDecodingException exception =
        assertThrows(
            ProposalDecodingException.class,
            () -> decoder.decode(invalid, StrictCandidateProposalDecoder.SCHEMA_VERSION));

    assertEquals(ProposalDecodingError.UNKNOWN_FIELD, exception.error());
    assertEquals("$.tenant_id", exception.path());
  }

  @Test
  void rejectsDuplicateJsonKeys() {
    String invalid =
        "{\"schema_version\":\"memory-candidate.v1\",\"schema_version\":\"memory-candidate.v1\",\"candidates\":[]}";

    ProposalDecodingException exception =
        assertThrows(
            ProposalDecodingException.class,
            () -> decoder.decode(invalid, StrictCandidateProposalDecoder.SCHEMA_VERSION));

    assertEquals(ProposalDecodingError.MALFORMED_JSON, exception.error());
  }

  @Test
  void rejectsNonExclusiveTemporalEnd() {
    String invalid = validJson().replace("2026-08-21T00:00:00Z", "2026-08-20T00:00:00Z");

    ProposalDecodingException exception =
        assertThrows(
            ProposalDecodingException.class,
            () -> decoder.decode(invalid, StrictCandidateProposalDecoder.SCHEMA_VERSION));

    assertEquals(ProposalDecodingError.INVALID_RANGE, exception.error());
    assertEquals("$.candidates[0].event_time", exception.path());
  }

  @Test
  void rejectsMismatchedSchemaVersion() {
    ProposalDecodingException exception =
        assertThrows(
            ProposalDecodingException.class,
            () -> decoder.decode(validJson(), "memory-candidate.v2"));

    assertEquals(ProposalDecodingError.SCHEMA_VERSION_MISMATCH, exception.error());
  }

  @Test
  void shipsDraft202012SchemaResource() throws IOException {
    try (var stream =
        StrictCandidateProposalDecoder.class
            .getClassLoader()
            .getResourceAsStream("schema/memory-candidate.v1.schema.json")) {
      assertNotNull(stream);
      String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(true, schema.contains("https://json-schema.org/draft/2020-12/schema"));
      assertEquals(true, schema.contains("\"additionalProperties\": false"));
    }
  }

  private static String validJson() {
    return """
        {
          "schema_version": "memory-candidate.v1",
          "candidates": [{
            "proposed_decision": "REMEMBER",
            "memory_type": "PROCEDURAL",
            "subject": {"kind": "AGENT", "label": null},
            "predicate": "behavior.review",
            "value": true,
            "normalized_content": "Review changes before publishing.",
            "event_time": {
              "original_text": "2026-08-20",
              "start": "2026-08-20T00:00:00Z",
              "end": "2026-08-21T00:00:00Z",
              "precision": "DAY",
              "confidence": 1.0
            },
            "valid_interval": null,
            "importance": 0.8,
            "confidence": 0.95,
            "sensitivity": ["NONE"],
            "candidate_relations": [{
              "type": "REINFORCES",
              "target_subject": "agent",
              "target_predicate": "behavior.review"
            }]
          }]
        }
        """;
  }
}
