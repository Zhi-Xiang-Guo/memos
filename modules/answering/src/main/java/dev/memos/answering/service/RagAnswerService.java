package dev.memos.answering.service;

import dev.memos.answering.model.Answer;
import dev.memos.answering.model.AnswerCommand;
import dev.memos.answering.model.AnswerEvent;
import dev.memos.answering.model.AnswerEvidence;
import dev.memos.answering.model.AnswerFailure;
import dev.memos.answering.model.ToolCall;
import dev.memos.answering.port.AnswerModelPort;
import dev.memos.answering.port.EvidenceSearchPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/** One read-only tool round. Authorization stays in the caller's immutable command. */
public final class RagAnswerService {
  private final EvidenceSearchPort search;
  private final AnswerModelPort model;
  private final Clock clock;
  private final Duration timeout;

  public RagAnswerService(
      EvidenceSearchPort search, AnswerModelPort model, Clock clock, Duration timeout) {
    this.search = Objects.requireNonNull(search);
    this.model = Objects.requireNonNull(model);
    this.clock = Objects.requireNonNull(clock);
    if (timeout == null || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("invalid answer timeout");
    this.timeout = timeout;
  }

  public Answer answer(AnswerCommand command, Consumer<AnswerEvent> events) {
    Instant deadline = clock.instant().plus(timeout);
    ToolCall tool = null;
    if (command.toolCalling()) {
      tool = model.plan(command.question(), deadline).orElse(null);
      check(deadline);
      if (tool == null) return finish(Answer.insufficient(), events);
      events.accept(new AnswerEvent("tool", java.util.Map.of("name", tool.name())));
    }
    check(deadline);
    AnswerEvidence evidence =
        search.search(command, tool == null ? command.question() : tool.query());
    check(deadline);
    events.accept(
        new AnswerEvent(
            "evidence",
            java.util.Map.of(
                "selectedIds",
                evidence.selectedIds(),
                "rankedIds",
                evidence.rankedIds(),
                "rerankOutcome",
                evidence.rerankOutcome())));
    if (evidence.selectedIds().isEmpty()) return finish(Answer.insufficient(), events);
    Answer answer =
        model.answer(
            command.question(),
            evidence,
            tool,
            deadline,
            delta -> {
              check(deadline);
              events.accept(new AnswerEvent("delta", java.util.Map.of("text", delta)));
            });
    check(deadline);
    if (!evidence.selectedIds().containsAll(answer.citations()))
      throw new AnswerFailure("UNKNOWN_CITATION", false);
    return finish(answer, events);
  }

  private Answer finish(Answer answer, Consumer<AnswerEvent> events) {
    events.accept(new AnswerEvent("answer", answer));
    return answer;
  }

  private void check(Instant deadline) {
    if (Thread.currentThread().isInterrupted()) throw new AnswerFailure("CANCELLED", false);
    if (!clock.instant().isBefore(deadline)) throw new AnswerFailure("ANSWER_TIMEOUT", false);
  }
}
