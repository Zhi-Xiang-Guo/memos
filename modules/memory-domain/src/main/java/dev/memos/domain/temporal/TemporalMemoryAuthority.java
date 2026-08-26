package dev.memos.domain.temporal;

public interface TemporalMemoryAuthority extends TemporalMemoryInspection {
  TransitionPlan materialize(MaterializeCandidateCommand command);

  TransitionPlan correct(CorrectAssertionCommand command);

  TransitionPlan invalidate(InvalidateAssertionCommand command);
}
