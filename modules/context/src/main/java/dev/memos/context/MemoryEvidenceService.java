package dev.memos.context;

import dev.memos.retrieval.HybridRetrievalService;
import dev.memos.retrieval.RetrievalQuery;
import dev.memos.retrieval.RetrievalResult;
import java.util.Objects;

/** Shared retrieval-to-context application operation for HTTP retrieval and answering. */
public final class MemoryEvidenceService {
  private final HybridRetrievalService retrieval;
  private final MemoryContextAssembler contexts;

  public MemoryEvidenceService(HybridRetrievalService retrieval, MemoryContextAssembler contexts) {
    this.retrieval = Objects.requireNonNull(retrieval);
    this.contexts = Objects.requireNonNull(contexts);
  }

  public Result retrieve(RetrievalQuery query, ContextBudget budget) {
    RetrievalResult result = retrieval.retrieve(query);
    return new Result(result, contexts.assemble(result.memories(), budget));
  }

  public record Result(RetrievalResult retrieval, ContextAssembly context) {}
}
