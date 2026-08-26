package dev.memos.materialization;

/** Evidence-safe application boundary for explicit temporal-memory mutations. */
public interface TemporalMemoryMutation {
  TemporalMutationResult correct(CorrectionSelection selection);

  TemporalMutationResult invalidate(InvalidationSelection selection);
}
