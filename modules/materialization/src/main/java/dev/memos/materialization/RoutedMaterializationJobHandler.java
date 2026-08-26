package dev.memos.materialization;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dispatches durable jobs to a handler selected only by the persisted job type. */
public final class RoutedMaterializationJobHandler implements MaterializationJobHandler {
  private final Map<JobType, MaterializationJobHandler> handlers;

  public RoutedMaterializationJobHandler(Map<JobType, MaterializationJobHandler> handlers) {
    Objects.requireNonNull(handlers, "handlers must not be null");
    if (handlers.isEmpty()) {
      throw new IllegalArgumentException("handlers must not be empty");
    }
    EnumMap<JobType, MaterializationJobHandler> copy = new EnumMap<>(JobType.class);
    handlers.forEach(
        (type, handler) -> {
          Objects.requireNonNull(type, "handler job type must not be null");
          Objects.requireNonNull(handler, "handler must not be null");
          if (copy.put(type, handler) != null) {
            throw new IllegalArgumentException("duplicate handler job type");
          }
        });
    this.handlers = Map.copyOf(copy);
  }

  public Set<JobType> supportedJobTypes() {
    return handlers.keySet();
  }

  @Override
  public JobHandlingResult handle(ClaimedJob job) throws JobHandlingException {
    Objects.requireNonNull(job, "job must not be null");
    MaterializationJobHandler handler = handlers.get(job.jobType());
    if (handler == null) {
      throw JobHandlingException.permanentFailure("UNSUPPORTED_JOB_TYPE");
    }
    return handler.handle(job);
  }
}
