package dev.memos.domain.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryCandidateProposalTest {
  @Test
  void temporalIntervalsAreStartInclusiveAndEndExclusive() {
    ProposedTimeRange range =
        new ProposedTimeRange(
            "August 2026",
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-09-01T00:00:00Z"),
            TemporalPrecision.MONTH,
            0.8);

    assertEquals(TemporalPrecision.MONTH, range.precision());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProposedTimeRange(
                "same instant", Instant.EPOCH, Instant.EPOCH, TemporalPrecision.EXACT, 1.0));
  }

  @Test
  void predicateMustBeNormalizedAndBounded() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MemoryCandidateProposal(
                ProposalDecision.REMEMBER,
                MemoryType.SEMANTIC,
                new CandidateSubject(SubjectKind.USER, null),
                "Not Normalized",
                new TextCandidateValue("value"),
                "Normalized content",
                null,
                null,
                0.5,
                0.9,
                Set.of(SensitivityCategory.NONE),
                List.of()));
  }
}
