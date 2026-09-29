package dev.memos.answering.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.memos.answering.model.*;
import dev.memos.answering.port.AnswerModelPort;
import dev.memos.domain.temporal.LineageScope;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RagAnswerServiceTest {
  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final LineageScope SCOPE = new LineageScope("tenant-a", "user-a", "agent-a");
  private static final AnswerCommand COMMAND =
      new AnswerCommand(SCOPE, "What is my theme?", true, false, false, 8, 1200);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

  private AnswerModelPort model(Answer value, boolean tool) {
    return new AnswerModelPort() {
      public Optional<ToolCall> plan(String question, Instant deadline) {
        return tool ? Optional.of(new ToolCall("search_memory", "editor theme")) : Optional.empty();
      }

      public Answer answer(
          String question,
          AnswerEvidence evidence,
          ToolCall call,
          Instant deadline,
          Consumer<String> delta) {
        delta.accept("partial JSON");
        return value;
      }
    };
  }

  @Test
  void toolCannotChangeScopeAndOnlySelectedEvidenceMayBeCited() {
    List<AnswerEvent> events = new ArrayList<>();
    var service =
        new RagAnswerService(
            (command, query) -> {
              assertEquals(SCOPE, command.scope());
              assertEquals("editor theme", query);
              return new AnswerEvidence("evidence", List.of(ID), List.of(ID), "DISABLED");
            },
            model(new Answer("Dark", false, List.of(ID)), true),
            CLOCK,
            Duration.ofSeconds(5));
    assertEquals("Dark", service.answer(COMMAND, events::add).answer());
    assertEquals(
        List.of("tool", "evidence", "delta", "answer"),
        events.stream().map(AnswerEvent::type).toList());
  }

  @Test
  void rejectsCitationsThatWereRankedButExcludedFromContext() {
    UUID other = UUID.randomUUID();
    var service =
        new RagAnswerService(
            (command, query) ->
                new AnswerEvidence("evidence", List.of(ID), List.of(ID, other), "DISABLED"),
            model(new Answer("Invented", false, List.of(other)), true),
            CLOCK,
            Duration.ofSeconds(5));
    List<AnswerEvent> events = new ArrayList<>();
    assertEquals(
        "UNKNOWN_CITATION",
        assertThrows(AnswerFailure.class, () -> service.answer(COMMAND, events::add)).code());
    assertTrue(events.stream().noneMatch(event -> event.type().equals("answer")));
  }

  @Test
  void noToolOrNoEvidenceAbstainsWithoutCallingAnswerModel() {
    AtomicInteger searches = new AtomicInteger();
    var service =
        new RagAnswerService(
            (command, query) -> {
              searches.incrementAndGet();
              return new AnswerEvidence("", List.of(), List.of(), "EMPTY");
            },
            model(new Answer("must not be used", false, List.of(ID)), false),
            CLOCK,
            Duration.ofSeconds(5));
    assertTrue(service.answer(COMMAND, event -> {}).abstain());
    assertEquals(0, searches.get());
    var empty =
        new RagAnswerService(
            (command, query) -> new AnswerEvidence("", List.of(), List.of(), "EMPTY"),
            model(new Answer("must not be used", false, List.of(ID)), true),
            CLOCK,
            Duration.ofSeconds(5));
    assertTrue(empty.answer(COMMAND, event -> {}).abstain());
  }

  @Test
  void toolAllowlistAndAnswerContractRejectUnsafeValues() {
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("delete_memory", "all"));
    assertThrows(IllegalArgumentException.class, () -> new Answer("Dark", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Answer("Unknown", true, List.of(ID)));
    assertThrows(IllegalArgumentException.class, () -> new Answer("Dark", false, List.of(ID, ID)));
  }

  @Test
  void interruptionStopsBeforeSearch() {
    var service =
        new RagAnswerService(
            (command, query) -> {
              fail("must not search");
              return null;
            },
            model(Answer.insufficient(), false),
            CLOCK,
            Duration.ofSeconds(5));
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          AnswerFailure.class,
          () ->
              service.answer(
                  new AnswerCommand(SCOPE, "question", false, false, false, 8, 1200), event -> {}));
    } finally {
      Thread.interrupted();
    }
  }
}
