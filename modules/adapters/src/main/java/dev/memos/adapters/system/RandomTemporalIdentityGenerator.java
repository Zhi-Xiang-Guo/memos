package dev.memos.adapters.system;

import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.TemporalIdentityGenerator;
import java.util.UUID;

public final class RandomTemporalIdentityGenerator implements TemporalIdentityGenerator {
  @Override
  public AssertionVersionId nextAssertionVersionId() {
    return new AssertionVersionId(UUID.randomUUID());
  }

  @Override
  public StateTransitionId nextStateTransitionId() {
    return new StateTransitionId(UUID.randomUUID());
  }
}
