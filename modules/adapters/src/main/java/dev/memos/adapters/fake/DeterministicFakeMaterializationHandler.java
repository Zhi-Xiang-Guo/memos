package dev.memos.adapters.fake;

import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.JobHandlingResult;
import dev.memos.materialization.MaterializationJobHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class DeterministicFakeMaterializationHandler implements MaterializationJobHandler {
  @Override
  public JobHandlingResult handle(ClaimedJob job) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("materialization handler must run outside a transaction");
    }
    return JobHandlingResult.WORK_DONE_NEEDS_COMPLETION;
  }
}
