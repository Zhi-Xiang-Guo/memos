package dev.memos.adapters.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.memos.materialization.JobFailureKind;
import dev.memos.materialization.ProjectionEmbeddingProviderException;
import dev.memos.materialization.ProjectionEmbeddingRequest;
import dev.memos.retrieval.EmbeddingRequest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleEmbeddingAdapterTest {
  private static final String VERSION = "deployment-test-v1";
  private static final String SUCCESS =
      "{\"model\":\"normalized-provider-name\",\"data\":[{\"index\":0,\"embedding\":[0.25,-0.5,0.75]}],\"usage\":{\"prompt_tokens\":7}}";

  @Test
  void usesOneProtocolForQueryProjectionAndTokenizerUsage() throws Exception {
    try (LocalProvider provider = new LocalProvider(200, SUCCESS)) {
      var adapter = provider.adapter(Duration.ofSeconds(2));
      var query = adapter.embed(new EmbeddingRequest("hello memory", VERSION));
      var projection = adapter.embed(new ProjectionEmbeddingRequest("hello memory", VERSION));
      assertThat(query.vector()).containsExactly(0.25f, -0.5f, 0.75f);
      assertThat(query.vector()).isEqualTo(projection.vector());
      assertThat(query.inputTokens()).isEqualTo(7);
      assertThat(projection.inputTokens()).isEqualTo(7);
      assertThat(query.modelVersion()).isEqualTo(VERSION);
      assertThat(query.provider()).isEqualTo("openai-compatible");
      assertThat(provider.authorization.get()).isEqualTo("Bearer test-api-key");
      Map<?, ?> body = JsonMapper.builder().build().readValue(provider.request.get(), Map.class);
      assertThat(body.get("model")).isEqualTo("router/model-name");
      assertThat(body.get("dimensions")).isEqualTo(3);
      assertThat(body.get("input")).isEqualTo(List.of("hello memory"));
      assertThat(body.get("encoding_format")).isEqualTo("float");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-json",
        "{\"data\":[]}",
        "{\"data\":[{\"index\":1,\"embedding\":[1,2,3]}],\"usage\":{\"prompt_tokens\":2}}",
        "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}],\"usage\":{\"prompt_tokens\":-1}}",
        "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}],\"usage\":{\"prompt_tokens\":1.5}}",
        "{\"data\":[{\"index\":0,\"embedding\":[1e100,2,3]}],\"usage\":{\"prompt_tokens\":2}}"
      })
  void malformedOrNonfiniteVectorsAndInexactUsageFailPermanently(String body) throws Exception {
    try (LocalProvider provider = new LocalProvider(200, body)) {
      assertProjectionFailure(provider, "MALFORMED_RESPONSE", JobFailureKind.PERMANENT);
    }
  }

  @Test
  void rejectsWrongDimensionAndVersionWithoutProviderCall() throws Exception {
    try (LocalProvider provider =
        new LocalProvider(200, SUCCESS.replace("0.25,-0.5,0.75", "0.25"))) {
      assertProjectionFailure(provider, "DIMENSION_MISMATCH", JobFailureKind.PERMANENT);
    }
    try (LocalProvider provider = new LocalProvider(200, SUCCESS)) {
      assertThatThrownBy(
              () ->
                  provider
                      .adapter(Duration.ofSeconds(2))
                      .embed(new ProjectionEmbeddingRequest("private-content", "retired-version")))
          .isInstanceOfSatisfying(
              ProjectionEmbeddingProviderException.class,
              failure ->
                  assertThat(failure.errorClass().value()).endsWith("MODEL_VERSION_MISMATCH"));
      assertThat(provider.request.get()).isNull();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "429,RATE_LIMIT,TRANSIENT",
    "503,SERVER_ERROR,TRANSIENT",
    "401,CLIENT_ERROR,PERMANENT"
  })
  void mapsHttpFailuresToRetryOrDeadWithoutLeakingContent(
      int status, String suffix, JobFailureKind kind) throws Exception {
    try (LocalProvider provider = new LocalProvider(status, "private-provider-body")) {
      assertProjectionFailure(provider, suffix, kind);
    }
  }

  @Test
  void boundsBodySizeAndRequestTime() throws Exception {
    try (LocalProvider provider = new LocalProvider(200, "x".repeat(4 * 1024 * 1024 + 1))) {
      assertProjectionFailure(provider, "RESPONSE_TOO_LARGE", JobFailureKind.PERMANENT);
    }
    try (LocalProvider provider = new LocalProvider(200, SUCCESS)) {
      provider.delayMillis = 200;
      assertThatThrownBy(
              () ->
                  provider
                      .adapter(Duration.ofMillis(20))
                      .embed(new EmbeddingRequest("private-content", VERSION)))
          .isInstanceOfSatisfying(
              OpenAiCompatibleEmbeddingException.class,
              failure ->
                  assertThat(failure.kind())
                      .isEqualTo(OpenAiCompatibleEmbeddingException.Kind.TIMEOUT));
    }
  }

  private static void assertProjectionFailure(
      LocalProvider provider, String suffix, JobFailureKind kind) {
    assertThatThrownBy(
            () ->
                provider
                    .adapter(Duration.ofSeconds(2))
                    .embed(new ProjectionEmbeddingRequest("private-content", VERSION)))
        .isInstanceOfSatisfying(
            ProjectionEmbeddingProviderException.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(kind);
              assertThat(failure.errorClass().value())
                  .isEqualTo("OPENAI_COMPATIBLE_EMBEDDING_" + suffix);
              assertThat(failure.toString())
                  .doesNotContain("private-content", "private-provider-body", "test-api-key");
            });
  }

  private static final class LocalProvider implements AutoCloseable {
    private final HttpServer server;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile long delayMillis;

    LocalProvider(int status, String body) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/v0/llm/embeddings",
          exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
              if (delayMillis > 0) Thread.sleep(delayMillis);
              byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(status, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    OpenAiCompatibleEmbeddingAdapter adapter(Duration timeout) {
      return new OpenAiCompatibleEmbeddingAdapter(
          HttpClient.newHttpClient(),
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v0/llm"),
          "test-api-key",
          "router/model-name",
          VERSION,
          3,
          timeout);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
