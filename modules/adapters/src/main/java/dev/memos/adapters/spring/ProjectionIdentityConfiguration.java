package dev.memos.adapters.spring;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Refuse silent model changes before the API/worker becomes ready. */
@Configuration(proxyBeanMethods = false)
public class ProjectionIdentityConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  SmartInitializingSingleton projectionIdentityGuard(
      JdbcTemplate jdbc,
      EmbeddingProperties embedding,
      TemporalMemoryProperties temporal,
      @Value("${memos.projection.allow-rebuild:false}") boolean allowRebuild) {
    return () ->
        jdbc.queryForObject(
            "SELECT memos.reconcile_projection(?, ?, ?, ?)",
            Long.class,
            embedding.modelVersion(),
            embedding.dimensions(),
            temporal.projectionPolicyVersion(),
            allowRebuild);
  }
}
