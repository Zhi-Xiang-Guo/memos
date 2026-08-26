package dev.memos.domain.temporal;

public enum TransitionOperation {
  IGNORE,
  CREATE,
  REINFORCE,
  SUPERSEDE,
  COEXIST,
  CONFLICT,
  INVALIDATE
}
