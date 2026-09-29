package dev.memos.adapters.answering;

import dev.memos.answering.model.AnswerCommand;
import dev.memos.answering.model.AnswerEvidence;
import dev.memos.answering.port.EvidenceSearchPort;
import dev.memos.context.ContextBudget;
import dev.memos.context.MemoryEvidenceService;
import dev.memos.retrieval.RetrievalMode;
import dev.memos.retrieval.RetrievalQuery;
import java.time.Clock;
import java.time.Duration;

public final class MemosEvidenceSearchAdapter implements EvidenceSearchPort {
  private final MemoryEvidenceService evidence;
  private final boolean rerankingEnabled;
  private final Duration rerankerTimeout;
  private final Clock clock;

  public MemosEvidenceSearchAdapter(
      MemoryEvidenceService evidence,
      boolean rerankingEnabled,
      Duration rerankerTimeout,
      Clock clock) {
    this.evidence = evidence;
    this.rerankingEnabled = rerankingEnabled;
    this.rerankerTimeout = rerankerTimeout;
    this.clock = clock;
  }

  @Override
  public AnswerEvidence search(AnswerCommand command, String query) {
    boolean rerank = command.rerank() && rerankingEnabled;
    var result =
        evidence.retrieve(
            new RetrievalQuery(
                command.scope(),
                query,
                command.vectorOnly() ? RetrievalMode.VECTOR_ONLY : RetrievalMode.HYBRID,
                command.limit(),
                40,
                null,
                null,
                null,
                rerank,
                rerank ? clock.instant().plus(rerankerTimeout) : null),
            new ContextBudget(command.maxTokens()));
    return new AnswerEvidence(
        result.context().rendered(),
        result.context().selectedVersionIds(),
        result.retrieval().memories().stream().map(item -> item.memory().versionId()).toList(),
        result.retrieval().trace().rerankOutcome());
  }
}
