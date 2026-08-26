package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.governance.CandidateWritePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CandidateExtractionService {
  private final StructuredCandidateExtractionPort provider;
  private final CandidateProposalDecoder decoder;
  private final CandidateWritePolicy writePolicy;
  private final ExtractionIdentifierGenerator identifierGenerator;

  public CandidateExtractionService(
      StructuredCandidateExtractionPort provider,
      CandidateProposalDecoder decoder,
      CandidateWritePolicy writePolicy,
      ExtractionIdentifierGenerator identifierGenerator) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
    this.writePolicy = Objects.requireNonNull(writePolicy, "writePolicy must not be null");
    this.identifierGenerator =
        Objects.requireNonNull(identifierGenerator, "identifierGenerator must not be null");
  }

  public CandidateExtractionEvaluation evaluate(
      ClaimedJob job, SourceForExtraction source, ExtractionProviderIdentity expectedProvider) {
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(expectedProvider, "expectedProvider must not be null");
    CandidateExtractionRequest request =
        new CandidateExtractionRequest(
            source.sourceEventId(),
            source.content(),
            source.metadata(),
            expectedProvider.promptVersion(),
            expectedProvider.schemaVersion());
    RawExtractionResponse response = provider.extract(request);
    if (!metadataMatches(response.metadata(), expectedProvider)) {
      return new InvalidExtractionEvaluation(
          response.metadata(),
          ProposalDecodingError.PROVIDER_METADATA_MISMATCH,
          "$.provider_metadata");
    }

    DecodedCandidateBatch decoded;
    try {
      decoded = decoder.decode(response.rawJson(), expectedProvider.schemaVersion());
    } catch (ProposalDecodingException exception) {
      return new InvalidExtractionEvaluation(
          response.metadata(), exception.error(), exception.path());
    }
    List<EvaluatedCandidate> candidates = new ArrayList<>(decoded.candidates().size());
    for (int ordinal = 0; ordinal < decoded.candidates().size(); ordinal++) {
      MemoryCandidateProposal proposal = decoded.candidates().get(ordinal);
      candidates.add(
          new EvaluatedCandidate(
              identifierGenerator.candidateIdFor(job, ordinal),
              ordinal,
              proposal,
              writePolicy.evaluate(proposal, source.policyContext(ordinal))));
    }
    return new ValidExtractionEvaluation(response.metadata(), candidates);
  }

  private static boolean metadataMatches(
      ProviderCallMetadata actual, ExtractionProviderIdentity expected) {
    return actual.provider().equals(expected.provider())
        && actual.modelVersion().equals(expected.modelVersion())
        && actual.promptVersion().equals(expected.promptVersion())
        && actual.schemaVersion().equals(expected.schemaVersion());
  }
}
