package dev.memos.api.http;

import dev.memos.materialization.JobId;
import dev.memos.materialization.MaterializationJob;
import dev.memos.materialization.MaterializationJobStore;
import dev.memos.materialization.ReplayResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/materialization-jobs")
public final class MaterializationJobController {
  private final MaterializationJobStore store;
  private final ScopeContextResolver scopeResolver;
  private final Clock clock;

  public MaterializationJobController(
      MaterializationJobStore store, ScopeContextResolver scopeResolver, Clock clock) {
    this.store = store;
    this.scopeResolver = scopeResolver;
    this.clock = clock;
  }

  @GetMapping("/{jobId}")
  MaterializationJobResponse find(@PathVariable UUID jobId, HttpServletRequest request) {
    var job =
        store
            .find(scopeResolver.resolve(request), new JobId(jobId))
            .orElseThrow(JobNotFoundException::new);
    return response(job);
  }

  @PostMapping("/{jobId}/replay")
  MaterializationJobResponse replay(@PathVariable UUID jobId, HttpServletRequest request) {
    var scope = scopeResolver.resolve(request);
    var typedJobId = new JobId(jobId);
    ReplayResult replay = store.replay(scope, typedJobId, clock.instant());
    if (replay == ReplayResult.NOT_FOUND) {
      throw new JobNotFoundException();
    }
    if (replay == ReplayResult.NOT_REPLAYABLE) {
      throw new JobNotReplayableException();
    }
    return store.find(scope, typedJobId).map(MaterializationJobController::response).orElseThrow();
  }

  private static MaterializationJobResponse response(MaterializationJob job) {
    return MaterializationJobResponse.from(job);
  }
}
