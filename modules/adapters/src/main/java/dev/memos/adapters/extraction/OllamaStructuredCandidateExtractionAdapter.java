package dev.memos.adapters.extraction;

import dev.memos.materialization.CandidateExtractionProviderException;
import dev.memos.materialization.CandidateExtractionRequest;
import dev.memos.materialization.ProviderCallMetadata;
import dev.memos.materialization.ProviderTokenUsage;
import dev.memos.materialization.RawExtractionResponse;
import dev.memos.materialization.StructuredCandidateExtractionPort;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Native Ollama structured extraction with startup digest and capability verification. */
public final class OllamaStructuredCandidateExtractionAdapter
    implements StructuredCandidateExtractionPort {
  public static final String PROVIDER = "ollama";

  private static final int MAX_HTTP_RESPONSE_BYTES = 1_048_576;
  private static final int MAX_STRUCTURED_OUTPUT_BYTES = 65_536;

  private final HttpClient client;
  private final JsonMapper mapper;
  private final URI tagsEndpoint;
  private final URI showEndpoint;
  private final URI chatEndpoint;
  private final String modelTag;
  private final String modelVersion;
  private final String expectedDigest;
  private final String promptVersion;
  private final String schemaVersion;
  private final int seed;
  private final Duration timeout;
  private final StructuredExtractionResources resources;
  private final Object parsedJsonSchema;

  public OllamaStructuredCandidateExtractionAdapter(
      HttpClient client,
      URI baseUrl,
      String modelTag,
      String modelVersion,
      String expectedDigest,
      String promptVersion,
      String schemaVersion,
      int seed,
      Duration timeout,
      StructuredExtractionResources resources) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = JsonMapper.builder().build();
    URI validatedBaseUrl = requireBaseUrl(baseUrl);
    this.tagsEndpoint = endpoint(validatedBaseUrl, "api/tags");
    this.showEndpoint = endpoint(validatedBaseUrl, "api/show");
    this.chatEndpoint = endpoint(validatedBaseUrl, "api/chat");
    this.modelTag = requireText(modelTag, "modelTag", 128);
    this.expectedDigest = requireDigest(expectedDigest);
    this.modelVersion = requireDigestVersion(modelVersion, expectedDigest);
    this.promptVersion = requireText(promptVersion, "promptVersion", 128);
    this.schemaVersion = requireText(schemaVersion, "schemaVersion", 128);
    if (seed < 0) {
      throw new IllegalArgumentException("seed must not be negative");
    }
    this.seed = seed;
    this.timeout = requireTimeout(timeout);
    this.resources = Objects.requireNonNull(resources, "resources must not be null");
    try {
      this.parsedJsonSchema = mapper.readValue(resources.jsonSchema(), Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("jsonSchema resource must contain valid JSON", exception);
    }
    verifyModel();
  }

  @Override
  public RawExtractionResponse extract(CandidateExtractionRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    assertNoActiveTransaction();
    requireMatchingVersions(request);

    Map<String, Object> options = new LinkedHashMap<>();
    options.put("temperature", 0);
    options.put("seed", seed);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", modelTag);
    body.put("messages", StructuredExtractionMessages.create(resources, request));
    body.put("stream", false);
    body.put("format", parsedJsonSchema);
    body.put("think", false);
    body.put("keep_alive", "10m");
    body.put("options", options);

    ProviderResponse response = post(chatEndpoint, body);
    Map<?, ?> value = response.value();
    if (!modelTag.equals(value.get("model"))) {
      throw permanent("MODEL_DRIFT");
    }
    if (!Boolean.TRUE.equals(value.get("done"))) {
      throw malformed("Ollama chat response is incomplete");
    }
    Map<?, ?> message = object(value.get("message"), "Ollama chat message");
    if (!"assistant".equals(message.get("role"))) {
      throw malformed("Ollama chat response role is invalid");
    }
    String rawJson = structuredOutput(message.get("content"));
    String createdAt = text(value.get("created_at"), "Ollama created_at", 100);
    long inputTokens = nonNegativeLong(value.get("prompt_eval_count"), "prompt_eval_count");
    long outputTokens = nonNegativeLong(value.get("eval_count"), "eval_count");
    return new RawExtractionResponse(
        rawJson,
        new ProviderCallMetadata(
            PROVIDER,
            modelVersion,
            promptVersion,
            schemaVersion,
            "ollama-" + createdAt,
            new ProviderTokenUsage(inputTokens, outputTokens),
            response.latency()));
  }

  private void verifyModel() {
    Map<?, ?> tags = get(tagsEndpoint).value();
    List<?> models = list(tags.get("models"), "Ollama models");
    String observedDigest = null;
    for (Object value : models) {
      Map<?, ?> model = object(value, "Ollama model");
      if (modelTag.equals(model.get("name"))) {
        observedDigest = digestValue(model.get("digest"), "Ollama model digest");
        break;
      }
    }
    if (observedDigest == null) {
      throw permanent("MODEL_NOT_FOUND");
    }
    if (!expectedDigest.equals(observedDigest)) {
      throw permanent("MODEL_DRIFT");
    }
    Map<?, ?> show = post(showEndpoint, Map.of("model", modelTag, "verbose", false)).value();
    List<?> capabilities = list(show.get("capabilities"), "Ollama model capabilities");
    if (capabilities.stream().noneMatch("completion"::equals)) {
      throw permanent("CAPABILITY_MISMATCH");
    }
  }

  private ProviderResponse get(URI uri) {
    return send(
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET()
            .build());
  }

  private ProviderResponse post(URI uri, Map<String, Object> body) {
    String encoded;
    try {
      encoded = mapper.writeValueAsString(body);
    } catch (JacksonException exception) {
      throw new IllegalStateException("cannot encode Ollama extraction request", exception);
    }
    return send(
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
            .build());
  }

  private ProviderResponse send(HttpRequest request) {
    long started = System.nanoTime();
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body = readBounded(response.body(), MAX_HTTP_RESPONSE_BYTES);
      Duration latency = Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
      checkStatus(response.statusCode());
      Object parsed;
      try {
        parsed = mapper.readValue(body, Object.class);
      } catch (JacksonException exception) {
        throw malformed("Ollama response is not valid JSON", exception);
      }
      return new ProviderResponse(object(parsed, "Ollama response"), latency);
    } catch (HttpTimeoutException exception) {
      throw transientFailure("TIMEOUT", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw transientFailure("TRANSPORT", exception);
    } catch (IOException exception) {
      throw transientFailure("TRANSPORT", exception);
    }
  }

  private static byte[] readBounded(InputStream stream, int maximumBytes) throws IOException {
    try (stream) {
      byte[] body = stream.readNBytes(maximumBytes + 1);
      if (body.length > maximumBytes) {
        throw permanent("RESPONSE_TOO_LARGE");
      }
      return body;
    }
  }

  private static void checkStatus(int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return;
    }
    if (statusCode == 429) {
      throw transientFailure("RATE_LIMIT", null);
    }
    if (statusCode >= 500) {
      throw transientFailure("SERVER_ERROR", null);
    }
    throw permanent("CLIENT_ERROR");
  }

  private void requireMatchingVersions(CandidateExtractionRequest request) {
    if (!promptVersion.equals(request.promptVersion())) {
      throw new IllegalArgumentException("request prompt version does not match the adapter");
    }
    if (!schemaVersion.equals(request.schemaVersion())) {
      throw new IllegalArgumentException("request schema version does not match the adapter");
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

  private static String requireDigestVersion(String version, String digest) {
    String required = "sha256:" + digest;
    if (!required.equals(version)) {
      throw new IllegalArgumentException("modelVersion must equal sha256:modelDigest for Ollama");
    }
    return version;
  }

  private static String digestValue(Object value, String name) {
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

  private static String text(Object value, String name, int maximumLength) {
    if (value instanceof String text && !text.isBlank() && text.length() <= maximumLength) {
      return text;
    }
    throw malformed(name + " must be a non-blank bounded string");
  }

  private static String structuredOutput(Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw malformed("Ollama structured output must be a non-blank string");
    }
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_STRUCTURED_OUTPUT_BYTES) {
      throw permanent("RESPONSE_TOO_LARGE");
    }
    return text;
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

  private static CandidateExtractionProviderException malformed(String message) {
    return malformed(message, null);
  }

  private static CandidateExtractionProviderException malformed(String message, Throwable cause) {
    return CandidateExtractionProviderException.permanentFailure(
        "OLLAMA_EXTRACTION_MALFORMED_RESPONSE", new IllegalArgumentException(message, cause));
  }

  private static CandidateExtractionProviderException permanent(String suffix) {
    return CandidateExtractionProviderException.permanentFailure(
        "OLLAMA_EXTRACTION_" + suffix, null);
  }

  private static CandidateExtractionProviderException transientFailure(
      String suffix, Throwable cause) {
    return CandidateExtractionProviderException.transientFailure(
        "OLLAMA_EXTRACTION_" + suffix, cause);
  }

  private static void assertNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "structured extraction must run outside a database transaction");
    }
  }

  private record ProviderResponse(Map<?, ?> value, Duration latency) {}
}
