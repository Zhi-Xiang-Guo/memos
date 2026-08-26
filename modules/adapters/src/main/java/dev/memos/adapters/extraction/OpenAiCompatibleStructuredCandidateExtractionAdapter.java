package dev.memos.adapters.extraction;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Optional synchronous adapter for OpenAI-compatible chat-completions JSON-schema APIs. */
public final class OpenAiCompatibleStructuredCandidateExtractionAdapter
    implements StructuredCandidateExtractionPort {
  public static final String PROVIDER = "openai-compatible";

  private static final int MAX_HTTP_RESPONSE_BYTES = 1_048_576;
  private static final int MAX_STRUCTURED_OUTPUT_BYTES = 65_536;

  private final HttpClient client;
  private final JsonMapper mapper;
  private final URI endpoint;
  private final String apiKey;
  private final String modelVersion;
  private final String promptVersion;
  private final String schemaVersion;
  private final Duration timeout;
  private final StructuredExtractionResources resources;
  private final Object parsedJsonSchema;

  public OpenAiCompatibleStructuredCandidateExtractionAdapter(
      HttpClient client,
      URI baseUrl,
      String apiKey,
      String modelVersion,
      String promptVersion,
      String schemaVersion,
      Duration timeout,
      StructuredExtractionResources resources) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = JsonMapper.builder().build();
    this.endpoint = endpoint(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
    this.apiKey = requireText(apiKey, "apiKey", 8_192);
    this.modelVersion = requireText(modelVersion, "modelVersion", 128);
    this.promptVersion = requireText(promptVersion, "promptVersion", 128);
    this.schemaVersion = requireText(schemaVersion, "schemaVersion", 128);
    this.timeout = requireTimeout(timeout);
    this.resources = Objects.requireNonNull(resources, "resources must not be null");
    try {
      this.parsedJsonSchema = mapper.readValue(resources.jsonSchema(), Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("jsonSchema resource must contain valid JSON", exception);
    }
  }

  @Override
  public RawExtractionResponse extract(CandidateExtractionRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    assertNoActiveTransaction();
    requireMatchingVersions(request);
    String requestJson = requestJson(request);
    HttpRequest httpRequest =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
            .build();

    long started = System.nanoTime();
    try {
      HttpResponse<InputStream> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body = readBounded(response.body(), MAX_HTTP_RESPONSE_BYTES);
      Duration latency = Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
      checkStatus(response.statusCode());
      return parseSuccess(body, latency);
    } catch (HttpTimeoutException exception) {
      throw new StructuredExtractionProviderException(
          StructuredExtractionProviderException.Kind.TIMEOUT,
          "structured extraction provider timed out",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new StructuredExtractionProviderException(
          StructuredExtractionProviderException.Kind.TRANSPORT,
          "structured extraction provider call was interrupted",
          exception);
    } catch (IOException exception) {
      throw new StructuredExtractionProviderException(
          StructuredExtractionProviderException.Kind.TRANSPORT,
          "structured extraction provider transport failed",
          exception);
    }
  }

  private String requestJson(CandidateExtractionRequest request) {
    Map<String, Object> jsonSchema = new LinkedHashMap<>();
    jsonSchema.put("name", "memory_candidate_v1");
    jsonSchema.put("strict", true);
    jsonSchema.put("schema", parsedJsonSchema);

    Map<String, Object> responseFormat = new LinkedHashMap<>();
    responseFormat.put("type", "json_schema");
    responseFormat.put("json_schema", jsonSchema);

    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", resources.prompt()));
    messages.add(Map.of("role", "user", "content", userMessage(request)));

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", modelVersion);
    requestBody.put("temperature", 0);
    requestBody.put("messages", messages);
    requestBody.put("response_format", responseFormat);
    try {
      return mapper.writeValueAsString(requestBody);
    } catch (JacksonException exception) {
      throw new IllegalStateException("cannot encode structured extraction request", exception);
    }
  }

  private static String userMessage(CandidateExtractionRequest request) {
    StringBuilder message = new StringBuilder();
    message.append("source_event_id: ").append(request.sourceEventId()).append('\n');
    request.metadata().entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEach(
            entry ->
                message
                    .append("metadata.")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append('\n'));
    message.append("<untrusted_source_content>\n");
    message.append(request.content());
    message.append("\n</untrusted_source_content>");
    return message.toString();
  }

  private RawExtractionResponse parseSuccess(byte[] body, Duration latency) {
    Object parsed;
    try {
      parsed = mapper.readValue(body, Object.class);
    } catch (JacksonException exception) {
      throw malformed("provider response is not valid JSON", exception);
    }
    Map<?, ?> root = object(parsed, "provider response");
    String providerCallId = text(root.get("id"), "provider response id", 200);
    List<?> choices = list(root.get("choices"), "provider response choices");
    if (choices.size() != 1) {
      throw malformed("provider response must contain exactly one choice");
    }
    Map<?, ?> choice = object(choices.getFirst(), "provider response choice");
    Map<?, ?> message = object(choice.get("message"), "provider response message");
    String rawJson = structuredOutput(message.get("content"));
    Map<?, ?> usage = object(root.get("usage"), "provider response usage");
    long inputTokens = nonNegativeLong(usage.get("prompt_tokens"), "prompt token count");
    long outputTokens = nonNegativeLong(usage.get("completion_tokens"), "completion token count");
    return new RawExtractionResponse(
        rawJson,
        new ProviderCallMetadata(
            PROVIDER,
            modelVersion,
            promptVersion,
            schemaVersion,
            providerCallId,
            new ProviderTokenUsage(inputTokens, outputTokens),
            latency));
  }

  private static byte[] readBounded(InputStream stream, int maximumBytes) throws IOException {
    try (stream) {
      byte[] body = stream.readNBytes(maximumBytes + 1);
      if (body.length > maximumBytes) {
        throw new StructuredExtractionProviderException(
            StructuredExtractionProviderException.Kind.RESPONSE_TOO_LARGE,
            "provider HTTP response exceeds the configured byte limit");
      }
      return body;
    }
  }

  private static void checkStatus(int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return;
    }
    if (statusCode == 429) {
      throw new StructuredExtractionProviderException(
          StructuredExtractionProviderException.Kind.RATE_LIMIT,
          "structured extraction provider returned HTTP 429");
    }
    if (statusCode >= 500) {
      throw new StructuredExtractionProviderException(
          StructuredExtractionProviderException.Kind.SERVER_ERROR,
          "structured extraction provider returned a server error");
    }
    throw new StructuredExtractionProviderException(
        StructuredExtractionProviderException.Kind.CLIENT_ERROR,
        "structured extraction provider rejected the request");
  }

  private void requireMatchingVersions(CandidateExtractionRequest request) {
    if (!promptVersion.equals(request.promptVersion())) {
      throw new IllegalArgumentException("request prompt version does not match the adapter");
    }
    if (!schemaVersion.equals(request.schemaVersion())) {
      throw new IllegalArgumentException("request schema version does not match the adapter");
    }
  }

  private static URI endpoint(URI baseUrl) {
    String scheme = baseUrl.getScheme();
    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("baseUrl must use http or https");
    }
    if (baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null) {
      throw new IllegalArgumentException("baseUrl must not contain query or fragment components");
    }
    String encoded = baseUrl.toString();
    return URI.create((encoded.endsWith("/") ? encoded : encoded + "/") + "chat/completions");
  }

  private static Duration requireTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("timeout must be positive and at most five minutes");
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
      throw malformed("provider structured output must be a non-blank string");
    }
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_STRUCTURED_OUTPUT_BYTES) {
      throw new StructuredExtractionProviderException(
          StructuredExtractionProviderException.Kind.RESPONSE_TOO_LARGE,
          "provider structured output exceeds 65536 UTF-8 bytes");
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

  private static StructuredExtractionProviderException malformed(String message) {
    return new StructuredExtractionProviderException(
        StructuredExtractionProviderException.Kind.MALFORMED_RESPONSE, message);
  }

  private static StructuredExtractionProviderException malformed(String message, Throwable cause) {
    return new StructuredExtractionProviderException(
        StructuredExtractionProviderException.Kind.MALFORMED_RESPONSE, message, cause);
  }

  private static void assertNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "structured extraction must run outside a database transaction");
    }
  }
}
