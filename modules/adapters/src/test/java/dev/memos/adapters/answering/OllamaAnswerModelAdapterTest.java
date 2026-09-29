package dev.memos.adapters.answering;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.memos.answering.model.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaAnswerModelAdapterTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String ANSWER =
      "{\"answer\":\"深色主题\",\"abstain\":false,\"citations\":[\"" + ID + "\"]}";

  @Test
  void nativeToolRoundAndIncrementalUnicodeJsonAreValidated() throws Exception {
    try (var server = new Server()) {
      List<Map<?, ?>> bodies = new CopyOnWriteArrayList<>();
      server.server.createContext(
          "/api/chat",
          exchange -> {
            Map<?, ?> body = JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            bodies.add(body);
            String response;
            if (body.containsKey("tools"))
              response =
                  JSON.writeValueAsString(
                      Map.of(
                          "model",
                          "test",
                          "done",
                          true,
                          "message",
                          Map.of(
                              "role",
                              "assistant",
                              "tool_calls",
                              List.of(
                                  Map.of(
                                      "function",
                                      Map.of(
                                          "name",
                                          "search_memory",
                                          "arguments",
                                          Map.of("query", "theme")))))));
            else
              response =
                  chunk(ANSWER.substring(0, 20), false)
                      + "\n"
                      + chunk(ANSWER.substring(20), true)
                      + "\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            for (byte value : bytes) exchange.getResponseBody().write(value);
            exchange.close();
          });
      server.server.start();
      var adapter = new OllamaAnswerModelAdapter(server.transport(Duration.ofSeconds(2), 2));
      ToolCall tool = adapter.plan("theme?", Instant.now().plusSeconds(5)).orElseThrow();
      List<String> deltas = new ArrayList<>();
      Answer answer =
          adapter.answer(
              "theme?",
              new AnswerEvidence(
                  "<memory>untrusted</memory>", List.of(ID), List.of(ID), "DISABLED"),
              tool,
              Instant.now().plusSeconds(5),
              deltas::add);
      assertThat(answer.answer()).isEqualTo("深色主题");
      assertThat(deltas).hasSize(2);
      assertThat(bodies.get(1).get("messages").toString())
          .contains("role=tool", "tool_name=search_memory");
    }
  }

  @Test
  void retriesTransientStatusBeforeOutputButNeverAfterPartialOutput() throws Exception {
    try (var server = new Server()) {
      AtomicInteger calls = new AtomicInteger();
      server.server.createContext(
          "/api/chat",
          exchange -> {
            int call = calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (call == 1) {
              exchange.sendResponseHeaders(503, -1);
              exchange.close();
              return;
            }
            byte[] bytes = (chunk("partial", false) + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.server.start();
      List<String> deltas = new ArrayList<>();
      assertThatThrownBy(
              () ->
                  server
                      .transport(Duration.ofSeconds(2), 3)
                      .chat(Map.of("stream", true), Instant.now().plusSeconds(5), deltas::add))
          .isInstanceOf(AnswerFailure.class)
          .hasMessage("INCOMPLETE_STREAM");
      assertThat(calls.get()).isEqualTo(2);
      assertThat(deltas).containsExactly("partial");
    }
  }

  @Test
  void bodyStallHasDeadlineAndDoesNotHoldRequestForever() throws Exception {
    try (var server = new Server()) {
      server.server.createContext(
          "/api/chat",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
              Thread.sleep(1500);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      server.server.start();
      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  server
                      .transport(Duration.ofMillis(150), 1)
                      .chat(Map.of("stream", true), Instant.now().plusSeconds(3), ignored -> {}))
          .isInstanceOf(AnswerFailure.class)
          .hasMessage("MODEL_TIMEOUT");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }
  }

  @Test
  void invalidSchemaAndInventedToolsAreRejectedWithoutRetry() throws Exception {
    for (String value :
        List.of(
            "{\"answer\":\"x\",\"abstain\":false,\"citations\":[]}",
            "{\"answer\":\"x\",\"abstain\":true,\"citations\":[],\"extra\":1}",
            "{\"answer\":\"x\",\"answer\":\"y\",\"abstain\":true,\"citations\":[]}"))
      assertThatThrownBy(() -> OllamaAnswerModelAdapter.decode(value))
          .isInstanceOf(AnswerFailure.class);
    try (var server = new Server()) {
      AtomicInteger calls = new AtomicInteger();
      server.server.createContext(
          "/api/chat",
          exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String response =
                JSON.writeValueAsString(
                    Map.of(
                        "model",
                        "test",
                        "done",
                        true,
                        "message",
                        Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(
                                Map.of(
                                    "function",
                                    Map.of(
                                        "name",
                                        "search_memory",
                                        "arguments",
                                        Map.of("query", "x", "tenantId", "victim")))))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.server.start();
      assertThatThrownBy(
              () ->
                  new OllamaAnswerModelAdapter(server.transport(Duration.ofSeconds(1), 3))
                      .plan("x", Instant.now().plusSeconds(5)))
          .isInstanceOf(AnswerFailure.class)
          .hasMessage("TOOL_ARGUMENTS");
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @Test
  void nativeRerankerReturnsOrderingAndRejectsFractionalUsage() throws Exception {
    try (var server = new Server()) {
      java.util.concurrent.atomic.AtomicReference<Number> usage =
          new java.util.concurrent.atomic.AtomicReference<>(12);
      UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");
      server.server.createContext(
          "/api/chat",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            String content =
                JSON.writeValueAsString(
                    Map.of("ordered_ids", List.of(second.toString(), ID.toString())));
            byte[] bytes =
                JSON.writeValueAsString(
                        Map.of(
                            "model",
                            "test",
                            "done",
                            true,
                            "prompt_eval_count",
                            usage.get(),
                            "message",
                            Map.of("role", "assistant", "content", content)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.server.start();
      var adapter = new OllamaRerankerAdapter(server.transport(Duration.ofSeconds(2), 1));
      var request =
          new dev.memos.retrieval.RerankRequest(
              "theme",
              List.of(
                  new dev.memos.retrieval.RerankCandidate(ID, "one", 0.5),
                  new dev.memos.retrieval.RerankCandidate(second, "two", 0.4)),
              "test",
              Instant.now().plusSeconds(5));
      assertThat(adapter.rerank(request).orderedVersionIds()).containsExactly(second, ID);
      usage.set(1.5);
      assertThatThrownBy(() -> adapter.rerank(request))
          .isInstanceOf(AnswerFailure.class)
          .hasMessage("RERANK_USAGE");
    }
  }

  private static String chunk(String content, boolean done) {
    return JSON.writeValueAsString(
        Map.of(
            "model",
            "test",
            "done",
            done,
            "message",
            Map.of("role", "assistant", "content", content)));
  }

  private static final class Server implements AutoCloseable {
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    Server() throws Exception {
      server.setExecutor(executor);
    }

    OllamaChatTransport transport(Duration timeout, int attempts) {
      return new OllamaChatTransport(
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
          URI.create("http://localhost:" + server.getAddress().getPort()),
          "test",
          Clock.systemUTC(),
          timeout,
          attempts);
    }

    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
