package dev.memos.adapters.metrics;

import dev.memos.materialization.CandidateExtractionRequest;
import dev.memos.materialization.RawExtractionResponse;
import dev.memos.materialization.StructuredCandidateExtractionPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;

/** Low-cardinality extraction telemetry; source content and identity never become tags. */
public final class InstrumentedStructuredCandidateExtractionPort
    implements StructuredCandidateExtractionPort {
  private final StructuredCandidateExtractionPort delegate;
  private final Counter success;
  private final Counter failure;
  private final Timer providerLatency;
  private final DistributionSummary inputTokens;
  private final DistributionSummary outputTokens;

  public InstrumentedStructuredCandidateExtractionPort(
      StructuredCandidateExtractionPort delegate, MeterRegistry registry, String provider) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(registry, "registry must not be null");
    String safeProvider = requireProvider(provider);
    success =
        Counter.builder("memos.extraction.calls")
            .tag("provider", safeProvider)
            .tag("outcome", "success")
            .register(registry);
    failure =
        Counter.builder("memos.extraction.calls")
            .tag("provider", safeProvider)
            .tag("outcome", "failure")
            .register(registry);
    providerLatency =
        Timer.builder("memos.extraction.provider.duration")
            .tag("provider", safeProvider)
            .register(registry);
    inputTokens =
        DistributionSummary.builder("memos.extraction.tokens")
            .tag("provider", safeProvider)
            .tag("direction", "input")
            .register(registry);
    outputTokens =
        DistributionSummary.builder("memos.extraction.tokens")
            .tag("provider", safeProvider)
            .tag("direction", "output")
            .register(registry);
  }

  @Override
  public RawExtractionResponse extract(CandidateExtractionRequest request) {
    try {
      RawExtractionResponse response = delegate.extract(request);
      success.increment();
      providerLatency.record(response.metadata().latency());
      inputTokens.record(response.metadata().tokenUsage().inputTokens());
      outputTokens.record(response.metadata().tokenUsage().outputTokens());
      return response;
    } catch (RuntimeException exception) {
      failure.increment();
      throw exception;
    }
  }

  private static String requireProvider(String provider) {
    if (provider == null || provider.isBlank() || provider.length() > 128) {
      throw new IllegalArgumentException("provider must be non-blank and bounded");
    }
    return provider;
  }
}
