package dev.memos.adapters.spring;

import dev.memos.adapters.extraction.DeterministicStructuredCandidateExtractionFake;
import dev.memos.adapters.extraction.OpenAiCompatibleStructuredCandidateExtractionAdapter;
import dev.memos.adapters.extraction.StructuredExtractionResources;
import dev.memos.adapters.metrics.InstrumentedStructuredCandidateExtractionPort;
import dev.memos.adapters.metrics.MicrometerOutboxWorkerTelemetry;
import dev.memos.adapters.observability.TracingMaterializationJobHandler;
import dev.memos.adapters.postgres.JdbcExtractionCommitStore;
import dev.memos.adapters.postgres.JdbcMaterializationJobStore;
import dev.memos.adapters.postgres.JdbcProjectionBuildStore;
import dev.memos.adapters.postgres.JdbcSourceExtractionStore;
import dev.memos.adapters.postgres.JdbcStorageObservationStore;
import dev.memos.adapters.postgres.JdbcTemporalMemoryAuthority;
import dev.memos.adapters.system.DeterministicExtractionIdentifierGenerator;
import dev.memos.adapters.system.RandomTemporalIdentityGenerator;
import dev.memos.adapters.system.RandomTemporalLineageIdentifier;
import dev.memos.adapters.system.ScheduledJobLeaseHeartbeat;
import dev.memos.domain.temporal.NormalizedAssertionDeduplication;
import dev.memos.domain.temporal.TemporalIdentityGenerator;
import dev.memos.domain.temporal.TemporalTransitionPlanner;
import dev.memos.governance.CandidateWritePolicy;
import dev.memos.governance.DeterministicCandidateWritePolicy;
import dev.memos.governance.WritePolicyConfiguration;
import dev.memos.materialization.CandidateExtractionJobHandler;
import dev.memos.materialization.CandidateExtractionService;
import dev.memos.materialization.CandidateProposalDecoder;
import dev.memos.materialization.ConfiguredPredicateCardinalityPolicy;
import dev.memos.materialization.ExponentialBackoffPolicy;
import dev.memos.materialization.ExtractionCommitStore;
import dev.memos.materialization.ExtractionIdentifierGenerator;
import dev.memos.materialization.ExtractionProviderIdentity;
import dev.memos.materialization.JobLeaseHeartbeat;
import dev.memos.materialization.MaterializationJobHandler;
import dev.memos.materialization.MaterializationJobStore;
import dev.memos.materialization.OutboxWorkerService;
import dev.memos.materialization.OutboxWorkerTelemetry;
import dev.memos.materialization.ProjectionBuildJobHandler;
import dev.memos.materialization.ProjectionBuildStore;
import dev.memos.materialization.ProjectionEmbeddingPort;
import dev.memos.materialization.RoutedMaterializationJobHandler;
import dev.memos.materialization.SourceExtractionStore;
import dev.memos.materialization.StorageObservationStore;
import dev.memos.materialization.StrictCandidateProposalDecoder;
import dev.memos.materialization.StructuredCandidateExtractionPort;
import dev.memos.materialization.TemporalCandidateMaterializationJobHandler;
import dev.memos.materialization.TemporalCandidateMaterializationStore;
import dev.memos.materialization.TemporalLineageIdentifier;
import dev.memos.materialization.WorkerId;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  WorkerProperties.class,
  ExtractionProperties.class,
  TemporalMemoryProperties.class
})
public class MaterializationConfiguration {
  @Bean
  MaterializationJobStore materializationJobStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new JdbcMaterializationJobStore(jdbc, new TransactionTemplate(transactionManager));
  }

  @Bean
  StorageObservationStore storageObservationStore(JdbcTemplate jdbc) {
    return new JdbcStorageObservationStore(jdbc);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  ExtractionCommitStore extractionCommitStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new JdbcExtractionCommitStore(jdbc, new TransactionTemplate(transactionManager));
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  SourceExtractionStore sourceExtractionStore(JdbcTemplate jdbc) {
    return new JdbcSourceExtractionStore(jdbc);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  ExtractionIdentifierGenerator extractionIdentifierGenerator() {
    return new DeterministicExtractionIdentifierGenerator();
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  CandidateProposalDecoder candidateProposalDecoder() {
    return new StrictCandidateProposalDecoder();
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  CandidateWritePolicy candidateWritePolicy() {
    return new DeterministicCandidateWritePolicy(WritePolicyConfiguration.safeDefaults());
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  StructuredCandidateExtractionPort structuredCandidateExtractionPort(
      ExtractionProperties properties, MeterRegistry registry) {
    String provider = required(properties.provider(), "memos.extraction.provider");
    StructuredCandidateExtractionPort delegate =
        switch (provider) {
          case "fake" ->
              new DeterministicStructuredCandidateExtractionFake(
                  required(properties.modelVersion(), "memos.extraction.model-version"),
                  required(properties.promptVersion(), "memos.extraction.prompt-version"),
                  required(properties.schemaVersion(), "memos.extraction.schema-version"),
                  java.util.Map.of(
                      DeterministicStructuredCandidateExtractionFake.DURABLE_PREFERENCE_SOURCE,
                      """
                  {"schema_version":"memory-candidate.v1","candidates":[{"proposed_decision":"REMEMBER","memory_type":"SEMANTIC","subject":{"kind":"USER","label":null},"predicate":"preference.editor.theme","value":"dark","normalized_content":"The user prefers a dark editor theme.","event_time":null,"valid_interval":null,"importance":0.7,"confidence":0.96,"sensitivity":["NONE"],"candidate_relations":[]}]}
                  """
                          .strip()));
          case "openai-compatible" ->
              new OpenAiCompatibleStructuredCandidateExtractionAdapter(
                  HttpClient.newBuilder().connectTimeout(properties.timeout()).build(),
                  URI.create(required(properties.baseUrl(), "memos.extraction.base-url")),
                  required(properties.apiKey(), "memos.extraction.api-key"),
                  required(properties.modelVersion(), "memos.extraction.model-version"),
                  required(properties.promptVersion(), "memos.extraction.prompt-version"),
                  required(properties.schemaVersion(), "memos.extraction.schema-version"),
                  properties.timeout(),
                  StructuredExtractionResources.loadV1());
          default -> throw new IllegalArgumentException("unsupported memos.extraction.provider");
        };
    return new InstrumentedStructuredCandidateExtractionPort(delegate, registry, provider);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  ExtractionProviderIdentity extractionProviderIdentity(ExtractionProperties properties) {
    String provider = required(properties.provider(), "memos.extraction.provider");
    String observedProvider = "fake".equals(provider) ? "fake" : "openai-compatible";
    return new ExtractionProviderIdentity(
        observedProvider,
        required(properties.modelVersion(), "memos.extraction.model-version"),
        required(properties.promptVersion(), "memos.extraction.prompt-version"),
        required(properties.schemaVersion(), "memos.extraction.schema-version"));
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  MaterializationJobHandler sourceExtractionJobHandler(
      Clock clock,
      SourceExtractionStore sourceStore,
      ExtractionCommitStore commitStore,
      StructuredCandidateExtractionPort provider,
      CandidateProposalDecoder decoder,
      CandidateWritePolicy writePolicy,
      ExtractionIdentifierGenerator identifiers,
      ExtractionProviderIdentity providerIdentity,
      ExtractionProperties properties) {
    CandidateExtractionService service =
        new CandidateExtractionService(provider, decoder, writePolicy, identifiers);
    return new TracingMaterializationJobHandler(
        new CandidateExtractionJobHandler(
            clock,
            sourceStore,
            commitStore,
            service,
            identifiers,
            providerIdentity,
            required(properties.policyVersion(), "memos.extraction.policy-version")));
  }

  @Bean
  TemporalIdentityGenerator temporalIdentityGenerator() {
    return new RandomTemporalIdentityGenerator();
  }

  @Bean
  TemporalTransitionPlanner temporalTransitionPlanner(TemporalIdentityGenerator identifiers) {
    return new TemporalTransitionPlanner(new NormalizedAssertionDeduplication(), identifiers);
  }

  @Bean
  JdbcTemporalMemoryAuthority jdbcTemporalMemoryAuthority(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      TemporalTransitionPlanner planner,
      TemporalMemoryProperties properties) {
    return new JdbcTemporalMemoryAuthority(
        jdbc,
        new TransactionTemplate(transactionManager),
        planner,
        required(properties.projectionPolicyVersion(), "memos.temporal.projection-policy-version"),
        required(properties.projectionModelVersion(), "memos.temporal.projection-model-version"));
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  ProjectionBuildStore projectionBuildStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      EmbeddingProperties embeddingProperties) {
    return new JdbcProjectionBuildStore(
        jdbc, new TransactionTemplate(transactionManager), embeddingProperties.dimensions());
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  TemporalLineageIdentifier temporalLineageIdentifier() {
    return new RandomTemporalLineageIdentifier();
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  MaterializationJobHandler temporalCandidateJobHandler(
      Clock clock,
      TemporalCandidateMaterializationStore store,
      TemporalTransitionPlanner planner,
      TemporalLineageIdentifier lineageIdentifier,
      TemporalMemoryProperties properties) {
    return new TemporalCandidateMaterializationJobHandler(
        clock,
        store,
        planner,
        new ConfiguredPredicateCardinalityPolicy(properties.setValuedPredicates()),
        lineageIdentifier,
        required(properties.projectionPolicyVersion(), "memos.temporal.projection-policy-version"));
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  MaterializationJobHandler projectionBuildJobHandler(
      Clock clock, ProjectionBuildStore store, ProjectionEmbeddingPort embeddingPort) {
    return new ProjectionBuildJobHandler(clock, store, embeddingPort);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  RoutedMaterializationJobHandler routedMaterializationJobHandler(
      @Qualifier("sourceExtractionJobHandler") MaterializationJobHandler sourceExtractionJobHandler,
      @Qualifier("temporalCandidateJobHandler")
          MaterializationJobHandler temporalCandidateJobHandler,
      @Qualifier("projectionBuildJobHandler") MaterializationJobHandler projectionBuildJobHandler) {
    return new RoutedMaterializationJobHandler(
        Map.of(
            dev.memos.materialization.JobType.MATERIALIZE_SOURCE,
            sourceExtractionJobHandler,
            dev.memos.materialization.JobType.CANDIDATE_MATERIALIZATION,
            temporalCandidateJobHandler,
            dev.memos.materialization.JobType.PROJECTION_BUILD,
            projectionBuildJobHandler));
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  OutboxWorkerTelemetry outboxWorkerTelemetry(MeterRegistry registry) {
    return new MicrometerOutboxWorkerTelemetry(registry);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  ScheduledJobLeaseHeartbeat jobLeaseHeartbeat(
      MaterializationJobStore store, OutboxWorkerTelemetry telemetry) {
    return new ScheduledJobLeaseHeartbeat(store, telemetry);
  }

  @Bean
  @ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
  OutboxWorkerService outboxWorkerService(
      Clock clock,
      MaterializationJobStore store,
      RoutedMaterializationJobHandler handler,
      OutboxWorkerTelemetry telemetry,
      JobLeaseHeartbeat leaseHeartbeat,
      WorkerProperties properties) {
    return new OutboxWorkerService(
        clock,
        store,
        handler,
        new ExponentialBackoffPolicy(properties.backoffBase(), properties.backoffCap()),
        telemetry,
        new WorkerId(effectiveWorkerId(properties.workerId())),
        properties.batchSize(),
        properties.leaseDuration(),
        handler.supportedJobTypes(),
        leaseHeartbeat);
  }

  private static String effectiveWorkerId(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException exception) {
      host = "unknown-host";
    }
    return host + "-" + UUID.randomUUID();
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must be configured");
    }
    return value;
  }
}
