package dev.memos.adapters.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.memos.materialization.JobFailureKind;
import dev.memos.materialization.ProjectionEmbeddingProviderException;
import dev.memos.materialization.ProjectionEmbeddingRequest;
import dev.memos.retrieval.EmbeddingRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaEmbeddingAdapterTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MODEL_TAG = "qwen3-embedding:0.6b";
  private static final String MODEL_VERSION = "sha256:model-snapshot-1";
  private static final String DIGEST = "a".repeat(64);

  @Test
  void verifiesPinnedModelAndReturnsStrictEmbeddingUsage() throws Exception {
    try (LocalProvider provider = LocalProvider.successful()) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));

      var result = adapter.embed(new EmbeddingRequest("hello memory", MODEL_VERSION));

      assertThat(result.vector()).containsExactly(0.25f, -0.5f, 0.75f);
      assertThat(result.provider()).isEqualTo("ollama");
      assertThat(result.modelVersion()).isEqualTo(MODEL_VERSION);
      assertThat(result.inputTokens()).isEqualTo(7);
      Map<?, ?> request = JSON.readValue(provider.embedRequestBody(), Map.class);
      assertThat(request.get("model")).isEqualTo(MODEL_TAG);
      assertThat(request.get("input")).isEqualTo(List.of("hello memory"));
      assertThat(request.get("truncate")).isEqualTo(false);
      assertThat(request.get("keep_alive")).isEqualTo("10m");
    }
  }

  @Test
  void rejectsDigestCapabilityAndMetadataDimensionDriftAtStartup() throws Exception {
    try (LocalProvider provider = LocalProvider.successful()) {
      assertKind(
          OllamaEmbeddingException.Kind.MODEL_DRIFT,
          () -> adapter(provider, "b".repeat(64), 3, Duration.ofSeconds(2)));
    }

    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST),
            show(List.of("completion"), 3),
            successfulEmbedding(),
            200,
            Duration.ZERO)) {
      assertKind(
          OllamaEmbeddingException.Kind.CAPABILITY_MISMATCH,
          () -> adapter(provider, DIGEST, 3, Duration.ofSeconds(2)));
    }

    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST),
            show(List.of("embedding"), 4),
            successfulEmbedding(),
            200,
            Duration.ZERO)) {
      assertKind(
          OllamaEmbeddingException.Kind.DIMENSION_MISMATCH,
          () -> adapter(provider, DIGEST, 3, Duration.ofSeconds(2)));
    }
  }

  @Test
  void rejectsMalformedAndWrongDimensionEmbeddingResponses() throws Exception {
    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST), show(List.of("embedding"), 3), "{not-json", 200, Duration.ZERO)) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));
      assertKind(
          OllamaEmbeddingException.Kind.MALFORMED_RESPONSE,
          () -> adapter.embed(new EmbeddingRequest("hello", MODEL_VERSION)));
    }

    String wrongDimensions =
        JSON.writeValueAsString(
            Map.of(
                "model",
                MODEL_TAG,
                "embeddings",
                List.of(List.of(1.0, 0.0)),
                "prompt_eval_count",
                2));
    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST), show(List.of("embedding"), 3), wrongDimensions, 200, Duration.ZERO)) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));
      assertKind(
          OllamaEmbeddingException.Kind.DIMENSION_MISMATCH,
          () -> adapter.embed(new EmbeddingRequest("hello", MODEL_VERSION)));
    }

    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST),
            show(List.of("embedding"), 3),
            "x".repeat(4 * 1_024 * 1_024 + 1),
            200,
            Duration.ZERO)) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));
      assertKind(
          OllamaEmbeddingException.Kind.RESPONSE_TOO_LARGE,
          () -> adapter.embed(new EmbeddingRequest("hello", MODEL_VERSION)));
    }
  }

  @Test
  void mapsTimeoutAndProviderFailuresWithoutLeakingResponseContent() throws Exception {
    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST),
            show(List.of("embedding"), 3),
            successfulEmbedding(),
            200,
            Duration.ofMillis(250))) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofMillis(30));
      assertKind(
          OllamaEmbeddingException.Kind.TIMEOUT,
          () -> adapter.embed(new EmbeddingRequest("hello", MODEL_VERSION)));
    }

    String sensitiveBody = "provider-body-must-not-appear-in-exception";
    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST), show(List.of("embedding"), 3), sensitiveBody, 503, Duration.ZERO)) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));

      assertThatThrownBy(
              () -> adapter.embed(new ProjectionEmbeddingRequest("secret memory", MODEL_VERSION)))
          .isInstanceOfSatisfying(
              ProjectionEmbeddingProviderException.class,
              exception -> {
                assertThat(exception.kind()).isEqualTo(JobFailureKind.TRANSIENT);
                assertThat(exception.errorClass().value())
                    .isEqualTo("OLLAMA_EMBEDDING_SERVER_ERROR");
                assertThat(exception.toString())
                    .doesNotContain(sensitiveBody)
                    .doesNotContain("secret memory");
              });
    }

    try (LocalProvider provider =
        new LocalProvider(
            tags(DIGEST), show(List.of("embedding"), 3), sensitiveBody, 400, Duration.ZERO)) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));

      assertThatThrownBy(
              () -> adapter.embed(new ProjectionEmbeddingRequest("secret memory", MODEL_VERSION)))
          .isInstanceOfSatisfying(
              ProjectionEmbeddingProviderException.class,
              exception -> {
                assertThat(exception.kind()).isEqualTo(JobFailureKind.PERMANENT);
                assertThat(exception.errorClass().value())
                    .isEqualTo("OLLAMA_EMBEDDING_CLIENT_ERROR");
              });
    }

    try (LocalProvider provider = LocalProvider.successful()) {
      OllamaEmbeddingAdapter adapter = adapter(provider, DIGEST, 3, Duration.ofSeconds(2));

      assertThatThrownBy(
              () ->
                  adapter.embed(
                      new ProjectionEmbeddingRequest("secret memory", "retired-model-version")))
          .isInstanceOfSatisfying(
              ProjectionEmbeddingProviderException.class,
              exception -> {
                assertThat(exception.kind()).isEqualTo(JobFailureKind.PERMANENT);
                assertThat(exception.errorClass().value())
                    .isEqualTo("OLLAMA_EMBEDDING_MODEL_VERSION_MISMATCH");
              });
      assertThat(provider.embedRequestBody()).isNull();
    }
  }

  private static OllamaEmbeddingAdapter adapter(
      LocalProvider provider, String digest, int dimensions, Duration timeout) {
    return new OllamaEmbeddingAdapter(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        provider.baseUrl(),
        MODEL_TAG,
        MODEL_VERSION,
        digest,
        dimensions,
        timeout);
  }

  private static void assertKind(OllamaEmbeddingException.Kind kind, ThrowingAction action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            OllamaEmbeddingException.class,
            exception -> assertThat(exception.kind()).isEqualTo(kind));
  }

  private static String tags(String digest) throws Exception {
    return JSON.writeValueAsString(
        Map.of("models", List.of(Map.of("name", MODEL_TAG, "digest", digest))));
  }

  private static String show(List<String> capabilities, int dimensions) throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "capabilities",
            capabilities,
            "model_info",
            Map.of("qwen3.embedding_length", dimensions)));
  }

  private static String successfulEmbedding() throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "model",
            MODEL_TAG,
            "embeddings",
            List.of(List.of(0.25, -0.5, 0.75)),
            "prompt_eval_count",
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
    private final byte[] embedResponse;
    private final int embedStatus;
    private final Duration embedDelay;
    private final AtomicReference<String> embedRequestBody = new AtomicReference<>();

    private LocalProvider(
        String tagsResponse,
        String showResponse,
        String embedResponse,
        int embedStatus,
        Duration embedDelay)
        throws IOException {
      this.tagsResponse = tagsResponse;
      this.showResponse = showResponse;
      this.embedResponse = embedResponse.getBytes(StandardCharsets.UTF_8);
      this.embedStatus = embedStatus;
      this.embedDelay = embedDelay;
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      this.executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext("/api/tags", this::handleTags);
      server.createContext("/api/show", this::handleShow);
      server.createContext("/api/embed", this::handleEmbed);
      server.start();
    }

    private static LocalProvider successful() throws Exception {
      return new LocalProvider(
          tags(DIGEST), show(List.of("embedding"), 3), successfulEmbedding(), 200, Duration.ZERO);
    }

    private URI baseUrl() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private String embedRequestBody() {
      return embedRequestBody.get();
    }

    private void handleTags(HttpExchange exchange) throws IOException {
      respond(exchange, 200, tagsResponse.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
    }

    private void handleShow(HttpExchange exchange) throws IOException {
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 200, showResponse.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
    }

    private void handleEmbed(HttpExchange exchange) throws IOException {
      embedRequestBody.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, embedStatus, embedResponse, embedDelay);
    }

    private static void respond(
        HttpExchange exchange, int statusCode, byte[] responseBody, Duration delay)
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
        exchange.sendResponseHeaders(statusCode, responseBody.length);
        exchange.getResponseBody().write(responseBody);
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
