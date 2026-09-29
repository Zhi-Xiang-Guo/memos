package dev.memos.adapters.answering;

import dev.memos.answering.model.AnswerFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded native NDJSON transport. A deadline covers both headers and body reads. */
public final class OllamaChatTransport {
  private static final int MAX_BYTES = 1_048_576;
  private static final int MAX_LINE_BYTES = 65_536;
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private final HttpClient client;
  private final URI endpoint;
  private final String model;
  private final Clock clock;
  private final Duration requestTimeout;
  private final int maxAttempts;

  public OllamaChatTransport(
      HttpClient client,
      URI base,
      String model,
      Clock clock,
      Duration requestTimeout,
      int maxAttempts) {
    if (base.getHost() == null
        || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
        || base.getRawUserInfo() != null
        || base.getRawQuery() != null
        || base.getRawFragment() != null)
      throw new IllegalArgumentException("invalid provider URL");
    if (model == null
        || model.isBlank()
        || model.length() > 128
        || requestTimeout == null
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0
        || maxAttempts < 1
        || maxAttempts > 3) throw new IllegalArgumentException("invalid provider configuration");
    this.client = client;
    this.endpoint = URI.create(base.toString().replaceAll("/+$", "") + "/api/chat");
    this.model = model;
    this.clock = clock;
    this.requestTimeout = requestTimeout;
    this.maxAttempts = maxAttempts;
  }

  public Map<?, ?> chat(Map<String, Object> body, Instant deadline, Consumer<String> delta) {
    AtomicBoolean emitted = new AtomicBoolean();
    Consumer<String> observed =
        text -> {
          if (!text.isEmpty()) {
            emitted.set(true);
            delta.accept(text);
          }
        };
    for (int attempt = 1; ; attempt++) {
      try {
        return once(body, deadline, observed);
      } catch (AnswerFailure failure) {
        if (!failure.retryable()
            || emitted.get()
            || attempt >= maxAttempts
            || !clock.instant().plusMillis(200).isBefore(deadline)) throw failure;
        try {
          Thread.sleep(ThreadLocalRandom.current().nextLong(50, 151) * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AnswerFailure("CANCELLED", false);
        }
      }
    }
  }

  private Map<?, ?> once(Map<String, Object> body, Instant deadline, Consumer<String> delta) {
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isNegative() || remaining.isZero())
      throw new AnswerFailure("ANSWER_TIMEOUT", false);
    Duration budget = remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout;
    AtomicReference<InputStream> active = new AtomicReference<>();
    AtomicBoolean cancelled = new AtomicBoolean();
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(budget)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var task =
          executor.submit(
              () -> {
                try {
                  HttpResponse<InputStream> response =
                      client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                  active.set(response.body());
                  try (InputStream input = response.body()) {
                    if (cancelled.get()) throw new AnswerFailure("CANCELLED", false);
                    int status = response.statusCode();
                    if (status < 200 || status >= 300)
                      throw new AnswerFailure(
                          "MODEL_HTTP_" + status, status == 429 || status >= 500);
                    if (!Boolean.TRUE.equals(body.get("stream"))) {
                      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                      if (bytes.length > MAX_BYTES)
                        throw new AnswerFailure("RESPONSE_TOO_LARGE", false);
                      Map<?, ?> value = parse(new String(bytes, StandardCharsets.UTF_8));
                      validate(value);
                      return value;
                    }
                    int total = 0;
                    ByteArrayOutputStream line = new ByteArrayOutputStream();
                    while (true) {
                      int next = input.read();
                      if (cancelled.get() || Thread.currentThread().isInterrupted())
                        throw new AnswerFailure("CANCELLED", false);
                      if (next < 0) {
                        if (line.size() > 0) {
                          Map<?, ?> terminal = chunk(line.toString(StandardCharsets.UTF_8), delta);
                          if (Boolean.TRUE.equals(terminal.get("done"))) return terminal;
                        }
                        throw new AnswerFailure("INCOMPLETE_STREAM", true);
                      }
                      if (++total > MAX_BYTES) throw new AnswerFailure("RESPONSE_TOO_LARGE", false);
                      if (next == '\n') {
                        if (line.size() == 0) continue;
                        Map<?, ?> value = chunk(line.toString(StandardCharsets.UTF_8), delta);
                        line.reset();
                        if (Boolean.TRUE.equals(value.get("done"))) return value;
                      } else {
                        if (line.size() >= MAX_LINE_BYTES)
                          throw new AnswerFailure("RESPONSE_TOO_LARGE", false);
                        line.write(next);
                      }
                    }
                  }
                } catch (IOException failure) {
                  throw new AnswerFailure("MODEL_TRANSPORT", true);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new AnswerFailure("CANCELLED", false);
                }
              });
      try {
        return task.get(Math.max(1, budget.toMillis()), TimeUnit.MILLISECONDS);
      } catch (TimeoutException failure) {
        throw new AnswerFailure("MODEL_TIMEOUT", true);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AnswerFailure("CANCELLED", false);
      } catch (ExecutionException failure) {
        if (failure.getCause() instanceof AnswerFailure known) throw known;
        throw new AnswerFailure("MODEL_PROTOCOL", false);
      } finally {
        cancelled.set(true);
        task.cancel(true);
        InputStream input = active.get();
        if (input != null)
          try {
            input.close();
          } catch (IOException ignored) {
            /* cancellation */
          }
      }
    }
  }

  private Map<?, ?> chunk(String line, Consumer<String> delta) {
    Map<?, ?> value = parse(line);
    if (!model.equals(value.get("model")) || !(value.get("done") instanceof Boolean))
      throw new AnswerFailure("MODEL_PROTOCOL", false);
    Map<?, ?> message = object(value.get("message"));
    if (!"assistant".equals(message.get("role"))
        || !(message.get("content") instanceof String content)
        || (message.containsKey("tool_calls")
            && !java.util.List.of().equals(message.get("tool_calls"))))
      throw new AnswerFailure("MODEL_PROTOCOL", false);
    delta.accept(content);
    return value;
  }

  private void validate(Map<?, ?> value) {
    if (!model.equals(value.get("model")) || !Boolean.TRUE.equals(value.get("done")))
      throw new AnswerFailure("MODEL_PROTOCOL", false);
  }

  public static Map<?, ?> parse(String text) {
    try {
      return object(JSON.readValue(text, Object.class));
    } catch (AnswerFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AnswerFailure("MODEL_SCHEMA", false);
    }
  }

  public static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw new AnswerFailure("MODEL_SCHEMA", false);
    return map;
  }

  public String model() {
    return model;
  }
}
