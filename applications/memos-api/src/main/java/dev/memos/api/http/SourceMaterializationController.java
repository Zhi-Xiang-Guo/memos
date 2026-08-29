package dev.memos.api.http;

import dev.memos.materialization.MaterializationJobStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/source-events")
public final class SourceMaterializationController {
  private final MaterializationJobStore store;
  private final ScopeContextResolver scopeResolver;

  public SourceMaterializationController(
      MaterializationJobStore store, ScopeContextResolver scopeResolver) {
    this.store = store;
    this.scopeResolver = scopeResolver;
  }

  @GetMapping("/{sourceEventId}/materialization")
  SourceMaterializationResponse find(@PathVariable UUID sourceEventId, HttpServletRequest request) {
    return store
        .findBySource(scopeResolver.resolve(request), sourceEventId)
        .map(SourceMaterializationResponse::from)
        .orElseThrow(SourceEventNotFoundException::new);
  }
}
