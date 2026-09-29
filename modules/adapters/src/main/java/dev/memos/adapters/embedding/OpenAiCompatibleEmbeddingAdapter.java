package dev.memos.adapters.embedding;

import dev.memos.adapters.embedding.OpenAiCompatibleEmbeddingException.Kind;
import dev.memos.materialization.ProjectionEmbedding;
import dev.memos.materialization.ProjectionEmbeddingProviderException;
import dev.memos.materialization.ProjectionEmbeddingRequest;
import dev.memos.retrieval.EmbeddingRequest;
import dev.memos.retrieval.EmbeddingResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** OpenAI-compatible /embeddings for both query and projection vectors, with exact usage. */
public final class OpenAiCompatibleEmbeddingAdapter implements EmbeddingAdapter {
  public static final String PROVIDER = "openai-compatible";
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient client;
  private final URI endpoint;
  private final String apiKey;
  private final String modelTag;
  private final String modelVersion;
  private final int dimensions;
  private final Duration timeout;

  public OpenAiCompatibleEmbeddingAdapter(
      HttpClient client,
      URI baseUrl,
      String apiKey,
      String modelTag,
      String modelVersion,
      int dimensions,
      Duration timeout) {
    this.client = Objects.requireNonNull(client);
    Objects.requireNonNull(baseUrl);
    if (!("http".equalsIgnoreCase(baseUrl.getScheme())
            || "https".equalsIgnoreCase(baseUrl.getScheme()))
        || baseUrl.getHost() == null
        || baseUrl.getRawQuery() != null
        || baseUrl.getRawFragment() != null
        || baseUrl.getRawUserInfo() != null) {
      throw new IllegalArgumentException(
          "baseUrl must be an HTTP(S) API base without credentials, query or fragment");
    }
    String base = baseUrl.toString();
    this.endpoint = URI.create((base.endsWith("/") ? base : base + "/") + "embeddings");
    this.apiKey = required(apiKey, "apiKey", 8192);
    this.modelTag = required(modelTag, "modelTag", 128);
    this.modelVersion = required(modelVersion, "modelVersion", 128);
    if (dimensions < 1 || dimensions > 2000) {
      throw new IllegalArgumentException("dimensions must be in [1,2000]");
    }
    this.dimensions = dimensions;
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("timeout must be positive and at most five minutes");
    }
    this.timeout = timeout;
  }

  @Override
  public EmbeddingResult embed(EmbeddingRequest request) {
    ProviderEmbedding result = embed(request.text(), request.modelVersion());
    return new EmbeddingResult(result.vector(), PROVIDER, modelVersion, result.inputTokens());
  }

  @Override
  public ProjectionEmbedding embed(ProjectionEmbeddingRequest request) {
    try {
      ProviderEmbedding result = embed(request.content(), request.modelVersion());
      return new ProjectionEmbedding(result.vector(), PROVIDER, modelVersion, result.inputTokens());
    } catch (OpenAiCompatibleEmbeddingException failure) {
      if (failure.kind().retryable()) {
        throw ProjectionEmbeddingProviderException.transientFailure(failure.getMessage(), failure);
      }
      throw ProjectionEmbeddingProviderException.permanentFailure(failure.getMessage(), failure);
    }
  }

  private ProviderEmbedding embed(String text, String requestedVersion) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("embedding provider calls must run outside a transaction");
    }
    if (!modelVersion.equals(requestedVersion)) {
      throw failure(Kind.MODEL_VERSION_MISMATCH);
    }
    Objects.requireNonNull(text);
    String body;
    try {
      body =
          mapper.writeValueAsString(
              Map.of(
                  "model",
                  modelTag,
                  "input",
                  List.of(text),
                  "dimensions",
                  dimensions,
                  "encoding_format",
                  "float"));
    } catch (JacksonException failure) {
      throw new IllegalStateException("cannot encode embedding request");
    }
    HttpRequest httpRequest =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<byte[]> response =
          client.send(
              httpRequest,
              ignored -> new OllamaEmbeddingAdapter.BoundedByteArraySubscriber(MAX_RESPONSE_BYTES));
      int status = response.statusCode();
      if (status == 429) throw failure(Kind.RATE_LIMIT);
      if (status >= 500) throw failure(Kind.SERVER_ERROR);
      if (status < 200 || status >= 300) throw failure(Kind.CLIENT_ERROR);
      Object parsed;
      try {
        parsed = mapper.readValue(response.body(), Object.class);
      } catch (JacksonException failure) {
        throw failure(Kind.MALFORMED_RESPONSE);
      }
      return decode(parsed);
    } catch (HttpTimeoutException failure) {
      throw failure(Kind.TIMEOUT);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw failure(Kind.TRANSPORT);
    } catch (IOException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof OllamaEmbeddingAdapter.ResponseTooLargeException) {
          throw failure(Kind.RESPONSE_TOO_LARGE);
        }
      }
      throw failure(Kind.TRANSPORT);
    }
  }

  private ProviderEmbedding decode(Object parsed) {
    if (!(parsed instanceof Map<?, ?> root)
        || !(root.get("data") instanceof List<?> data)
        || data.size() != 1
        || !(data.getFirst() instanceof Map<?, ?> item)
        || wholeNumber(item.get("index")) != 0
        || !(item.get("embedding") instanceof List<?> rawVector)
        || !(root.get("usage") instanceof Map<?, ?> usage)) {
      throw failure(Kind.MALFORMED_RESPONSE);
    }
    if (rawVector.size() != dimensions) throw failure(Kind.DIMENSION_MISMATCH);
    List<Float> vector = new ArrayList<>(dimensions);
    for (Object value : rawVector) {
      if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) {
        throw failure(Kind.MALFORMED_RESPONSE);
      }
      vector.add(number.floatValue());
    }
    return new ProviderEmbedding(List.copyOf(vector), wholeNumber(usage.get("prompt_tokens")));
  }

  private static long wholeNumber(Object value) {
    if (!(value instanceof Number number)) throw failure(Kind.MALFORMED_RESPONSE);
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      if (result < 0) throw failure(Kind.MALFORMED_RESPONSE);
      return result;
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw failure(Kind.MALFORMED_RESPONSE);
    }
  }

  private static String required(String value, String name, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new IllegalArgumentException(name + " must be nonblank and bounded");
    }
    return value;
  }

  private static OpenAiCompatibleEmbeddingException failure(Kind kind) {
    return new OpenAiCompatibleEmbeddingException(kind);
  }

  private record ProviderEmbedding(List<Float> vector, long inputTokens) {}
}
