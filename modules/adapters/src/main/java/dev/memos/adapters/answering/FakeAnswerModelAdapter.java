package dev.memos.adapters.answering;

import dev.memos.answering.model.Answer;
import dev.memos.answering.model.AnswerEvidence;
import dev.memos.answering.model.ToolCall;
import dev.memos.answering.port.AnswerModelPort;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

public final class FakeAnswerModelAdapter implements AnswerModelPort {
  @Override
  public Optional<ToolCall> plan(String question, Instant deadline) {
    return Optional.of(new ToolCall("search_memory", question));
  }

  @Override
  public Answer answer(
      String question,
      AnswerEvidence evidence,
      ToolCall tool,
      Instant deadline,
      Consumer<String> delta) {
    delta.accept("{\"answer\":\"现有证据不足，无法回答。\",\"abstain\":true,\"citations\":[]}");
    return Answer.insufficient();
  }
}
