package dev.memos.answering.model;

import dev.memos.domain.temporal.LineageScope;
import java.util.Objects;

public record AnswerCommand(
    LineageScope scope,
    String question,
    boolean toolCalling,
    boolean rerank,
    boolean vectorOnly,
    int limit,
    int maxTokens) {
  public AnswerCommand {
    Objects.requireNonNull(scope);
    if (question == null || question.isBlank() || question.length() > 4096)
      throw new IllegalArgumentException("question must contain 1 to 4096 characters");
    if (limit < 1 || limit > 20 || maxTokens < 64 || maxTokens > 8192)
      throw new IllegalArgumentException("invalid answer evidence limits");
  }
}
