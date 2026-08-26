package dev.memos.domain.temporal;

import java.util.Objects;

public record TransitionContext(
    TransitionActor actor, TransitionSource source, String policyVersion) {
  public TransitionContext {
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(source, "source must not be null");
    policyVersion = TemporalValidation.text(policyVersion, "policyVersion", 128);
  }
}
