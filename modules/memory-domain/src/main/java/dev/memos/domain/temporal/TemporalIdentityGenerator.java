package dev.memos.domain.temporal;

public interface TemporalIdentityGenerator {
  AssertionVersionId nextAssertionVersionId();

  StateTransitionId nextStateTransitionId();
}
