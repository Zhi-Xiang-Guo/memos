package dev.memos.adapters.embedding;

import dev.memos.materialization.ProjectionEmbedding;
import dev.memos.materialization.ProjectionEmbeddingProviderException;
import dev.memos.materialization.ProjectionEmbeddingRequest;
import dev.memos.retrieval.EmbeddingRequest;
import dev.memos.retrieval.EmbeddingResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Digest-pinned Ollama embedding adapter with bounded requests and strict response decoding. */
public final class OllamaEmbeddingAdapter implements EmbeddingAdapter {
  public static final String PROVIDER = "ollama";

  private static final int MAX_RESPONSE_BYTES = 4 * 1_024 * 1_024;
  private static final int MAX_PGVECTOR_HNSW_DIMENSIONS = 2_000;

  private final HttpClient client;
  private final JsonMapper mapper;
  private final URI tagsEndpoint;
  private final URI showEndpoint;
  private final URI embedEndpoint;
  private final String modelTag;
  private final String modelVersion;
  private final String expectedDigest;
  private final int expectedDimensions;
  private final Duration timeout;

  public OllamaEmbeddingAdapter(
      HttpClient client,
      URI baseUrl,
      String modelTag,
      String modelVersion,
      String expectedDigest,
      int expectedDimensions,
      Duration timeout) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = JsonMapper.builder().build();
    URI validatedBaseUrl = requireBaseUrl(baseUrl);
    this.tagsEndpoint = endpoint(validatedBaseUrl, "api/tags");
    this.showEndpoint = endpoint(validatedBaseUrl, "api/show");
    this.embedEndpoint = endpoint(validatedBaseUrl, "api/embed");
    this.modelTag = requireText(modelTag, "modelTag", 128);
    this.modelVersion = requireText(modelVersion, "modelVersion", 128);
    this.expectedDigest = requireDigest(expectedDigest);
    if (expectedDimensions < 1 || expectedDimensions > MAX_PGVECTOR_HNSW_DIMENSIONS) {
      throw new IllegalArgumentException("expectedDimensions must be in [1,2000]");
    }
    this.expectedDimensions = expectedDimensions;
    this.timeout = requireTimeout(timeout);
    verifyModel();
  }

  @Override
  public EmbeddingResult embed(EmbeddingRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    ProviderEmbedding embedded = embed(request.text(), request.modelVersion());
    return new EmbeddingResult(embedded.vector(), PROVIDER, modelVersion, embedded.inputTokens());
  }

  @Override
  public ProjectionEmbedding embed(ProjectionEmbeddingRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    try {
      ProviderEmbedding embedded = embed(request.content(), request.modelVersion());
      return new ProjectionEmbedding(
          embedded.vector(), PROVIDER, modelVersion, embedded.inputTokens());
    } catch (OllamaEmbeddingException exception) {
      String errorClass = "OLLAMA_EMBEDDING_" + exception.kind().name();
      if (exception.kind().retryable()) {
        throw ProjectionEmbeddingProviderException.transientFailure(errorClass, exception);
      }
      throw ProjectionEmbeddingProviderException.permanentFailure(errorClass, exception);
    }
  }

  private ProviderEmbedding embed(String text, String requestedModelVersion) {
    assertNoActiveTransaction();
    requireModelVersion(requestedModelVersion);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", modelTag);
    body.put("input", List.of(text));
    body.put("truncate", false);
    body.put("keep_alive", "10m");
    Map<?, ?> response = post(embedEndpoint, body);
    if (!modelTag.equals(response.get("model"))) {
      throw failure(
          OllamaEmbeddingException.Kind.MODEL_DRIFT,
          "Ollama embedding response model differs from the configured tag");
    }
    List<?> embeddings = list(response.get("embeddings"), "Ollama embeddings");
    if (embeddings.size() != 1) {
      throw malformed("Ollama embedding response must contain exactly one vector");
    }
    List<?> rawVector = list(embeddings.getFirst(), "Ollama embedding vector");
    if (rawVector.size() != expectedDimensions) {
      throw failure(
          OllamaEmbeddingException.Kind.DIMENSION_MISMATCH,
          "Ollama embedding dimensions differ from the configured projection");
    }
    List<Float> vector = new ArrayList<>(rawVector.size());
    for (Object value : rawVector) {
      if (!(value instanceof Number number)) {
        throw malformed("Ollama embedding vector contains a non-numeric value");
      }
      float converted = number.floatValue();
      if (!Float.isFinite(converted)) {
        throw malformed("Ollama embedding vector contains a non-finite value");
      }
      vector.add(converted);
    }
    long inputTokens = nonNegativeLong(response.get("prompt_eval_count"), "prompt_eval_count");
    return new ProviderEmbedding(List.copyOf(vector), inputTokens);
  }

  private void verifyModel() {
    Map<?, ?> tags = get(tagsEndpoint);
    List<?> models = list(tags.get("models"), "Ollama models");
    String observedDigest = null;
    for (Object value : models) {
      Map<?, ?> model = object(value, "Ollama model");
      if (modelTag.equals(model.get("name"))) {
        observedDigest = requireDigestValue(model.get("digest"), "Ollama model digest");
        break;
      }
    }
    if (observedDigest == null) {
      throw failure(
          OllamaEmbeddingException.Kind.MODEL_NOT_FOUND,
          "configured Ollama embedding model is absent");
    }
    if (!expectedDigest.equals(observedDigest)) {
      throw failure(
          OllamaEmbeddingException.Kind.MODEL_DRIFT,
          "configured Ollama embedding model digest drifted");
    }

    Map<?, ?> show = post(showEndpoint, Map.of("model", modelTag, "verbose", false));
    List<?> capabilities = list(show.get("capabilities"), "Ollama model capabilities");
    if (capabilities.stream().noneMatch("embedding"::equals)) {
      throw failure(
          OllamaEmbeddingException.Kind.CAPABILITY_MISMATCH,
          "configured Ollama model lacks the embedding capability");
    }
    Map<?, ?> modelInfo = object(show.get("model_info"), "Ollama model_info");
    Long observedDimensions = null;
    for (Map.Entry<?, ?> entry : modelInfo.entrySet()) {
      if (entry.getKey() instanceof String key && key.endsWith(".embedding_length")) {
        observedDimensions = nonNegativeLong(entry.getValue(), "embedding_length");
      }
    }
    if (observedDimensions == null || observedDimensions != expectedDimensions) {
      throw failure(
          OllamaEmbeddingException.Kind.DIMENSION_MISMATCH,
          "configured Ollama model metadata has unexpected embedding dimensions");
    }
  }

  private Map<?, ?> get(URI uri) {
    return send(
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET()
            .build());
  }

  private Map<?, ?> post(URI uri, Map<String, Object> body) {
    String encoded;
    try {
      encoded = mapper.writeValueAsString(body);
    } catch (JacksonException exception) {
      throw new IllegalStateException("cannot encode Ollama request", exception);
    }
    return send(
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
            .build());
  }

  private Map<?, ?> send(HttpRequest request) {
    try {
      HttpResponse<byte[]> response =
          client.send(request, ignored -> new BoundedByteArraySubscriber(MAX_RESPONSE_BYTES));
      checkStatus(response.statusCode());
      Object parsed;
      try {
        parsed = mapper.readValue(response.body(), Object.class);
      } catch (JacksonException exception) {
        throw malformed("Ollama response is not valid JSON", exception);
      }
      return object(parsed, "Ollama response");
    } catch (HttpTimeoutException exception) {
      throw failure(
          OllamaEmbeddingException.Kind.TIMEOUT, "Ollama embedding request timed out", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure(
          OllamaEmbeddingException.Kind.TRANSPORT,
          "Ollama embedding request was interrupted",
          exception);
    } catch (IOException exception) {
      if (causedByResponseLimit(exception)) {
        throw failure(
            OllamaEmbeddingException.Kind.RESPONSE_TOO_LARGE,
            "Ollama response exceeded the byte limit",
            exception);
      }
      throw failure(
          OllamaEmbeddingException.Kind.TRANSPORT, "Ollama embedding transport failed", exception);
    }
  }

  private static boolean causedByResponseLimit(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ResponseTooLargeException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void checkStatus(int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return;
    }
    if (statusCode == 429) {
      throw failure(OllamaEmbeddingException.Kind.RATE_LIMIT, "Ollama returned HTTP 429");
    }
    if (statusCode >= 500) {
      throw failure(OllamaEmbeddingException.Kind.SERVER_ERROR, "Ollama returned a server error");
    }
    throw failure(OllamaEmbeddingException.Kind.CLIENT_ERROR, "Ollama rejected the request");
  }

  private void requireModelVersion(String requested) {
    if (!modelVersion.equals(requested)) {
      throw failure(
          OllamaEmbeddingException.Kind.MODEL_VERSION_MISMATCH,
          "requested embedding model version is not configured");
    }
  }

  private static URI requireBaseUrl(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    String scheme = baseUrl.getScheme();
    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("baseUrl must use http or https");
    }
    if (baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null) {
      throw new IllegalArgumentException("baseUrl must not contain query or fragment components");
    }
    return baseUrl;
  }

  private static URI endpoint(URI baseUrl, String path) {
    String encoded = baseUrl.toString();
    return URI.create((encoded.endsWith("/") ? encoded : encoded + "/") + path);
  }

  private static Duration requireTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
      throw new IllegalArgumentException("timeout must be positive and at most thirty minutes");
    }
    return timeout;
  }

  private static String requireText(String value, String name, int maximumLength) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(name + " must be non-blank and bounded");
    }
    return value;
  }

  private static String requireDigest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("expectedDigest must be a full lowercase SHA-256 digest");
    }
    return value;
  }

  private static String requireDigestValue(Object value, String name) {
    if (value instanceof String digest && digest.matches("[0-9a-f]{64}")) {
      return digest;
    }
    throw malformed(name + " must be a full lowercase SHA-256 digest");
  }

  private static Map<?, ?> object(Object value, String name) {
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    throw malformed(name + " must be an object");
  }

  private static List<?> list(Object value, String name) {
    if (value instanceof List<?> list) {
      return list;
    }
    throw malformed(name + " must be an array");
  }

  private static long nonNegativeLong(Object value, String name) {
    if (!(value instanceof Number number)) {
      throw malformed(name + " must be a number");
    }
    try {
      long result = new BigDecimal(number.toString()).longValueExact();
      if (result < 0) {
        throw malformed(name + " must not be negative");
      }
      return result;
    } catch (ArithmeticException exception) {
      throw malformed(name + " must be a whole number", exception);
    }
  }

  private static void assertNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("embedding provider calls must run outside a transaction");
    }
  }

  private static OllamaEmbeddingException malformed(String message) {
    return failure(OllamaEmbeddingException.Kind.MALFORMED_RESPONSE, message);
  }

  private static OllamaEmbeddingException malformed(String message, Throwable cause) {
    return failure(OllamaEmbeddingException.Kind.MALFORMED_RESPONSE, message, cause);
  }

  private static OllamaEmbeddingException failure(
      OllamaEmbeddingException.Kind kind, String message) {
    return new OllamaEmbeddingException(kind, message);
  }

  private static OllamaEmbeddingException failure(
      OllamaEmbeddingException.Kind kind, String message, Throwable cause) {
    return new OllamaEmbeddingException(kind, message, cause);
  }

  private static final class BoundedByteArraySubscriber
      implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximumBytes;
    private final ByteArrayOutputStream output;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private int receivedBytes;

    private BoundedByteArraySubscriber(int maximumBytes) {
      this.maximumBytes = maximumBytes;
      this.output = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
      Objects.requireNonNull(incoming, "subscription must not be null");
      if (subscription != null) {
        incoming.cancel();
        return;
      }
      subscription = incoming;
      incoming.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) {
        return;
      }
      long additional = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
      if (additional > maximumBytes - receivedBytes) {
        subscription.cancel();
        body.completeExceptionally(new ResponseTooLargeException());
        return;
      }
      for (ByteBuffer buffer : buffers) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
        receivedBytes += bytes.length;
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      body.complete(output.toByteArray());
    }
  }

  private static final class ResponseTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;
  }

  private record ProviderEmbedding(List<Float> vector, long inputTokens) {}
}
