package dev.memos.materialization;

@FunctionalInterface
public interface CandidateProposalDecoder {
  DecodedCandidateBatch decode(String rawJson, String expectedSchemaVersion);
}
