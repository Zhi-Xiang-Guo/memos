package dev.memos.api.http;

import dev.memos.materialization.StorageObservationStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/operations/storage")
public final class StorageObservationController {
  private final StorageObservationStore store;
  private final ScopeContextResolver scopeResolver;

  public StorageObservationController(
      StorageObservationStore store, ScopeContextResolver scopeResolver) {
    this.store = store;
    this.scopeResolver = scopeResolver;
  }

  @GetMapping
  StorageObservationResponse observe(HttpServletRequest request) {
    return StorageObservationResponse.from(store.observe(scopeResolver.resolve(request)));
  }
}
