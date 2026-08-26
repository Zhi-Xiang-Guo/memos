package dev.memos.materialization;

@FunctionalInterface
public interface StructuredCandidateExtractionPort {
  RawExtractionResponse extract(CandidateExtractionRequest request);
}
