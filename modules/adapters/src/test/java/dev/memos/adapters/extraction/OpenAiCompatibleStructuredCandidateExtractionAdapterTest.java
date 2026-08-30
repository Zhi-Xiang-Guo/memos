package dev.memos.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.memos.materialization.CandidateExtractionProviderException;
import dev.memos.materialization.CandidateExtractionRequest;
import dev.memos.materialization.JobFailureKind;
import dev.memos.materialization.RawExtractionResponse;
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

class OpenAiCompatibleStructuredCandidateExtractionAdapterTest {
  private static final String SYNTHETIC_API_KEY = "synthetic-api-key-for-local-test";
  private static final String EMPTY_STRUCTURED_OUTPUT =
      "{\"schema_version\":\"memory-candidate.v1\",\"candidates\":[]}";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void returnsBoundedStructuredOutputAndObservedUsage() throws Exception {
    String response =
        JSON.writeValueAsString(
            Map.of(
                "id",
                "provider-call-1",
                "choices",
                List.of(Map.of("message", Map.of("content", EMPTY_STRUCTURED_OUTPUT))),
                "usage",
                Map.of("prompt_tokens", 11, "completion_tokens", 7)));
    try (LocalProvider provider = new LocalProvider(200, response, Duration.ZERO)) {
      OpenAiCompatibleStructuredCandidateExtractionAdapter adapter =
          adapter(provider, Duration.ofSeconds(2));

      RawExtractionResponse result = adapter.extract(request());

      assertThat(result.rawJson()).isEqualTo(EMPTY_STRUCTURED_OUTPUT);
      assertThat(result.metadata().provider()).isEqualTo("openai-compatible");
      assertThat(result.metadata().modelVersion()).isEqualTo("model-snapshot-1");
      assertThat(result.metadata().promptVersion()).isEqualTo("candidate-extraction-v1");
      assertThat(result.metadata().schemaVersion()).isEqualTo("memory-candidate.v1");
      assertThat(result.metadata().providerCallId()).isEqualTo("provider-call-1");
      assertThat(result.metadata().tokenUsage().inputTokens()).isEqualTo(11);
      assertThat(result.metadata().tokenUsage().outputTokens()).isEqualTo(7);
      assertThat(result.metadata().latency()).isGreaterThanOrEqualTo(Duration.ZERO);
      assertThat(provider.authorization()).isEqualTo("Bearer " + SYNTHETIC_API_KEY);
      assertThat(provider.requestBody())
          .contains("\"model\":\"deployment-model\"")
          .contains("\"seed\":42")
          .doesNotContain("\"model\":\"model-snapshot-1\"")
          .contains("memory-candidate.v1")
          .contains("<untrusted_source_content>")
          .contains("I prefer a dark editor theme.");
    }
  }

  @Test
  void classifiesRateLimitAndServerFailuresWithoutLeakingResponseOrKey() throws Exception {
    for (StatusExpectation expectation :
        List.of(
            new StatusExpectation(429, "OPENAI_COMPATIBLE_EXTRACTION_RATE_LIMIT"),
            new StatusExpectation(503, "OPENAI_COMPATIBLE_EXTRACTION_SERVER_ERROR"))) {
      try (LocalProvider provider =
          new LocalProvider(
              expectation.statusCode(),
              "provider-body-must-not-appear-in-exception",
              Duration.ZERO)) {
        OpenAiCompatibleStructuredCandidateExtractionAdapter adapter =
            adapter(provider, Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.extract(request()))
            .isInstanceOfSatisfying(
                CandidateExtractionProviderException.class,
                exception -> {
                  assertThat(exception.kind()).isEqualTo(JobFailureKind.TRANSIENT);
                  assertThat(exception.errorClass().value()).isEqualTo(expectation.errorClass());
                  assertThat(exception.toString())
                      .doesNotContain(SYNTHETIC_API_KEY)
                      .doesNotContain("provider-body-must-not-appear-in-exception");
                });
      }
    }
  }

  @Test
  void mapsRequestTimeoutWithoutExternalNetwork() throws Exception {
    try (LocalProvider provider =
        new LocalProvider(200, successfulResponse(), Duration.ofMillis(250))) {
      OpenAiCompatibleStructuredCandidateExtractionAdapter adapter =
          adapter(provider, Duration.ofMillis(30));

      assertThatThrownBy(() -> adapter.extract(request()))
          .isInstanceOfSatisfying(
              CandidateExtractionProviderException.class,
              exception ->
                  assertThat(exception.errorClass().value())
                      .isEqualTo("OPENAI_COMPATIBLE_EXTRACTION_TIMEOUT"));
    }
  }

  @Test
  void rejectsMalformedEnvelopeAndMalformedUsage() throws Exception {
    try (LocalProvider malformedJson = new LocalProvider(200, "{not-json", Duration.ZERO)) {
      assertMalformed(adapter(malformedJson, Duration.ofSeconds(2)));
    }

    String missingUsageField =
        JSON.writeValueAsString(
            Map.of(
                "id",
                "provider-call-2",
                "choices",
                List.of(Map.of("message", Map.of("content", EMPTY_STRUCTURED_OUTPUT))),
                "usage",
                Map.of("prompt_tokens", 4)));
    try (LocalProvider malformedUsage = new LocalProvider(200, missingUsageField, Duration.ZERO)) {
      assertMalformed(adapter(malformedUsage, Duration.ofSeconds(2)));
    }
  }

  @Test
  void rejectsOversizedStructuredOutput() throws Exception {
    String oversizedOutput = "x".repeat(65_537);
    String response =
        JSON.writeValueAsString(
            Map.of(
                "id",
                "provider-call-large",
                "choices",
                List.of(Map.of("message", Map.of("content", oversizedOutput))),
                "usage",
                Map.of("prompt_tokens", 1, "completion_tokens", 1)));
    try (LocalProvider provider = new LocalProvider(200, response, Duration.ZERO)) {
      assertThatThrownBy(() -> adapter(provider, Duration.ofSeconds(2)).extract(request()))
          .isInstanceOfSatisfying(
              CandidateExtractionProviderException.class,
              exception ->
                  assertThat(exception.errorClass().value())
                      .isEqualTo("OPENAI_COMPATIBLE_EXTRACTION_RESPONSE_TOO_LARGE"));
    }
  }

  @Test
  void loadsPinnedPromptAndSchemaResources() {
    StructuredExtractionResources resources = StructuredExtractionResources.loadV1();

    assertThat(resources.prompt())
        .contains("untrusted source content")
        .doesNotContain(SYNTHETIC_API_KEY);
    assertThat(resources.jsonSchema())
        .contains("\"schema_version\"")
        .contains("\"memory-candidate.v1\"")
        .contains("\"additionalProperties\": false");
  }

  private static void assertMalformed(
      OpenAiCompatibleStructuredCandidateExtractionAdapter adapter) {
    assertThatThrownBy(() -> adapter.extract(request()))
        .isInstanceOfSatisfying(
            CandidateExtractionProviderException.class,
            exception ->
                assertThat(exception.errorClass().value())
                    .isEqualTo("OPENAI_COMPATIBLE_EXTRACTION_MALFORMED_RESPONSE"));
  }

  private static OpenAiCompatibleStructuredCandidateExtractionAdapter adapter(
      LocalProvider provider, Duration timeout) {
    return new OpenAiCompatibleStructuredCandidateExtractionAdapter(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        provider.baseUrl(),
        SYNTHETIC_API_KEY,
        "deployment-model",
        "model-snapshot-1",
        "candidate-extraction-v1",
        "memory-candidate.v1",
        42,
        timeout,
        StructuredExtractionResources.loadV1());
  }

  private static CandidateExtractionRequest request() {
    return new CandidateExtractionRequest(
        UUID.fromString("019c36de-8938-7000-8000-000000000002"),
        "I prefer a dark editor theme.",
        Map.of("source_type", "CONVERSATION_MESSAGE"),
        "candidate-extraction-v1",
        "memory-candidate.v1");
  }

  private static String successfulResponse() throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "id",
            "provider-call-timeout",
            "choices",
            List.of(Map.of("message", Map.of("content", EMPTY_STRUCTURED_OUTPUT))),
            "usage",
            Map.of("prompt_tokens", 1, "completion_tokens", 1)));
  }

  private record StatusExpectation(int statusCode, String errorClass) {}

  private static final class LocalProvider implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final int statusCode;
    private final byte[] responseBody;
    private final Duration delay;

    private LocalProvider(int statusCode, String responseBody, Duration delay) throws IOException {
      this.statusCode = statusCode;
      this.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
      this.delay = delay;
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      this.executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext("/v1/chat/completions", this::handle);
      server.start();
    }

    private URI baseUrl() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private String authorization() {
      return authorization.get();
    }

    private String requestBody() {
      return requestBody.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
