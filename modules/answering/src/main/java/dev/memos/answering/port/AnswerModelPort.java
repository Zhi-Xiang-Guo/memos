package dev.memos.answering.port;

import dev.memos.answering.model.Answer;
import dev.memos.answering.model.AnswerEvidence;
import dev.memos.answering.model.ToolCall;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

public interface AnswerModelPort {
  Optional<ToolCall> plan(String question, Instant deadline);

  Answer answer(
      String question,
      AnswerEvidence evidence,
      ToolCall tool,
      Instant deadline,
      Consumer<String> delta);
}
