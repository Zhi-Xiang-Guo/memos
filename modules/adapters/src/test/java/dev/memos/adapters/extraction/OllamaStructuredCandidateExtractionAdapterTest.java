package dev.memos.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.memos.materialization.CandidateExtractionProviderException;
import dev.memos.materialization.CandidateExtractionRequest;
import dev.memos.materialization.JobFailureKind;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaStructuredCandidateExtractionAdapterTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MODEL_TAG = "qwen3:4b";
  private static final String DIGEST = "a".repeat(64);
  private static final String MODEL_VERSION = "sha256:" + DIGEST;
  private static final String EMPTY_OUTPUT =
      "{\"schema_version\":\"memory-candidate.v1\",\"candidates\":[]}";

  @Test
  void verifiesPinnedModelAndUsesNativeStructuredChatContract() throws Exception {
    try (LocalProvider provider = LocalProvider.successful()) {
      OllamaStructuredCandidateExtractionAdapter adapter =
          adapter(provider, DIGEST, MODEL_VERSION, Duration.ofSeconds(2));

      var result = adapter.extract(request());

      assertThat(result.rawJson()).isEqualTo(EMPTY_OUTPUT);
      assertThat(result.metadata().provider()).isEqualTo("ollama");
      assertThat(result.metadata().modelVersion()).isEqualTo(MODEL_VERSION);
      assertThat(result.metadata().providerCallId()).isEqualTo("ollama-2026-08-30T00:00:00Z");
      assertThat(result.metadata().tokenUsage().inputTokens()).isEqualTo(11);
      assertThat(result.metadata().tokenUsage().outputTokens()).isEqualTo(7);

      Map<?, ?> body = JSON.readValue(provider.chatRequestBody(), Map.class);
      assertThat(body.get("model")).isEqualTo(MODEL_TAG);
      assertThat(body.get("stream")).isEqualTo(false);
      assertThat(body.get("think")).isEqualTo(false);
      assertThat(body.get("keep_alive")).isEqualTo("10m");
      assertThat(body.get("format")).isInstanceOf(Map.class);
      assertThat(body.get("options")).isEqualTo(Map.of("temperature", 0, "seed", 42));
      assertThat(body.toString())
          .contains("<untrusted_source_content>")
          .contains("I prefer concise answers.");
    }
  }

  @Test
  void rejectsVersionDigestAndCapabilityDriftAtStartup() throws Exception {
    try (LocalProvider provider = LocalProvider.successful()) {
      assertThatThrownBy(() -> adapter(provider, DIGEST, "qwen3:4b", Duration.ofSeconds(2)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sha256:modelDigest");
    }

    try (LocalProvider provider = LocalProvider.successful()) {
      assertPermanent(
          "OLLAMA_EXTRACTION_MODEL_DRIFT",
          () ->
              adapter(provider, "b".repeat(64), "sha256:" + "b".repeat(64), Duration.ofSeconds(2)));
    }

    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST), show(List.of("embedding")), successfulChat(), 200, Duration.ZERO)) {
      assertPermanent(
          "OLLAMA_EXTRACTION_CAPABILITY_MISMATCH",
          () -> adapter(provider, DIGEST, MODEL_VERSION, Duration.ofSeconds(2)));
    }
  }

  @Test
  void classifiesRuntimeDriftTimeoutAndProviderErrorsWithoutLeakingBodies() throws Exception {
    String drifted =
        JSON.writeValueAsString(
            Map.of(
                "model",
                "different-tag",
                "created_at",
                "2026-08-30T00:00:00Z",
                "message",
                Map.of("role", "assistant", "content", EMPTY_OUTPUT),
                "done",
                true,
                "prompt_eval_count",
                1,
                "eval_count",
                1));
    try (LocalProvider provider =
        new LocalProvider(tags(DIGEST), show(List.of("completion")), drifted, 200, Duration.ZERO)) {
      OllamaStructuredCandidateExtractionAdapter adapter =
          adapter(provider, DIGEST, MODEL_VERSION, Duration.ofSeconds(2));
      assertPermanent("OLLAMA_EXTRACTION_MODEL_DRIFT", () -> adapter.extract(request()));
    }

    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST),
            show(List.of("completion")),
            successfulChat(),
            200,
            Duration.ofMillis(250))) {
      OllamaStructuredCandidateExtractionAdapter adapter =
          adapter(provider, DIGEST, MODEL_VERSION, Duration.ofMillis(30));
      assertThatThrownBy(() -> adapter.extract(request()))
          .isInstanceOfSatisfying(
              CandidateExtractionProviderException.class,
              failure -> {
                assertThat(failure.kind()).isEqualTo(JobFailureKind.TRANSIENT);
                assertThat(failure.errorClass().value()).isEqualTo("OLLAMA_EXTRACTION_TIMEOUT");
              });
    }

    String sensitiveBody = "provider-body-must-not-appear";
    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST), show(List.of("completion")), sensitiveBody, 400, Duration.ZERO)) {
      OllamaStructuredCandidateExtractionAdapter adapter =
          adapter(provider, DIGEST, MODEL_VERSION, Duration.ofSeconds(2));
      assertThatThrownBy(() -> adapter.extract(request()))
          .isInstanceOfSatisfying(
              CandidateExtractionProviderException.class,
              failure -> {
                assertThat(failure.kind()).isEqualTo(JobFailureKind.PERMANENT);
                assertThat(failure.errorClass().value())
                    .isEqualTo("OLLAMA_EXTRACTION_CLIENT_ERROR");
                assertThat(failure.toString())
                    .doesNotContain(sensitiveBody)
                    .doesNotContain("I prefer concise answers.");
              });
    }
  }

  private static OllamaStructuredCandidateExtractionAdapter adapter(
      LocalProvider provider, String digest, String version, Duration timeout) {
    return new OllamaStructuredCandidateExtractionAdapter(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        provider.baseUrl(),
        MODEL_TAG,
        version,
        digest,
        "candidate-extraction-v1",
        "memory-candidate.v1",
        42,
        timeout,
        StructuredExtractionResources.loadV1());
  }

  private static CandidateExtractionRequest request() {
    return new CandidateExtractionRequest(
        UUID.fromString("019c36de-8938-7000-8000-000000000002"),
        "I prefer concise answers.",
        Map.of("source_type", "CONVERSATION_MESSAGE"),
        "candidate-extraction-v1",
        "memory-candidate.v1");
  }

  private static void assertPermanent(String errorClass, ThrowingAction action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            CandidateExtractionProviderException.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(JobFailureKind.PERMANENT);
              assertThat(failure.errorClass().value()).isEqualTo(errorClass);
            });
  }

  private static String tags(String digest) throws Exception {
    return JSON.writeValueAsString(
        Map.of("models", List.of(Map.of("name", MODEL_TAG, "digest", digest))));
  }

  private static String show(List<String> capabilities) throws Exception {
    return JSON.writeValueAsString(
        Map.of("capabilities", capabilities, "details", Map.of(), "model_info", Map.of()));
  }

  private static String successfulChat() throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "model",
            MODEL_TAG,
            "created_at",
            "2026-08-30T00:00:00Z",
            "message",
            Map.of("role", "assistant", "content", EMPTY_OUTPUT),
            "done",
            true,
            "prompt_eval_count",
            11,
            "eval_count",
            7));
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final class LocalProvider implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final String tagsResponse;
    private final String showResponse;
    private final byte[] chatResponse;
    private final int chatStatus;
    private final Duration chatDelay;
    private final AtomicReference<String> chatRequestBody = new AtomicReference<>();

    private LocalProvider(
        String tagsResponse,
        String showResponse,
        String chatResponse,
        int chatStatus,
        Duration chatDelay)
        throws IOException {
      this.tagsResponse = tagsResponse;
      this.showResponse = showResponse;
      this.chatResponse = chatResponse.getBytes(StandardCharsets.UTF_8);
      this.chatStatus = chatStatus;
      this.chatDelay = chatDelay;
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      this.executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext("/api/tags", this::handleTags);
      server.createContext("/api/show", this::handleShow);
      server.createContext("/api/chat", this::handleChat);
      server.start();
    }

    private static LocalProvider successful() throws Exception {
      return new LocalProvider(
          tags(DIGEST), show(List.of("completion")), successfulChat(), 200, Duration.ZERO);
    }

    private URI baseUrl() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private String chatRequestBody() {
      return chatRequestBody.get();
    }

    private void handleTags(HttpExchange exchange) throws IOException {
      respond(exchange, 200, tagsResponse.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
    }

    private void handleShow(HttpExchange exchange) throws IOException {
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 200, showResponse.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
    }

    private void handleChat(HttpExchange exchange) throws IOException {
      chatRequestBody.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, chatStatus, chatResponse, chatDelay);
    }

    private static void respond(HttpExchange exchange, int status, byte[] response, Duration delay)
        throws IOException {
      try (exchange) {
        if (!delay.isZero()) {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
      } catch (IOException ignoredAfterClientTimeout) {
        // A timed-out local client can close the exchange before the fixture server responds.
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.close();
    }
  }
}
