package dev.memos.adapters.observability;

import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.JobHandlingException;
import dev.memos.materialization.MaterializationJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class TracingMaterializationJobHandler implements MaterializationJobHandler {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(TracingMaterializationJobHandler.class);

  private final MaterializationJobHandler delegate;

  public TracingMaterializationJobHandler(MaterializationJobHandler delegate) {
    this.delegate = delegate;
  }

  @Override
  public void handle(ClaimedJob job) throws JobHandlingException {
    MDC.put("traceId", job.traceId());
    try {
      delegate.handle(job);
      LOGGER.info(
          "materialization handler jobId={} jobType={} attempt={} outcome=success",
          job.jobId(),
          job.jobType(),
          job.attempt());
    } catch (JobHandlingException exception) {
      LOGGER.warn(
          "materialization handler jobId={} jobType={} attempt={} outcome=failure errorClass={}",
          job.jobId(),
          job.jobType(),
          job.attempt(),
          exception.errorClass());
      throw exception;
    } catch (RuntimeException exception) {
      LOGGER.warn(
          "materialization handler jobId={} jobType={} attempt={} outcome=unexpected_failure",
          job.jobId(),
          job.jobType(),
          job.attempt());
      throw exception;
    } finally {
      MDC.remove("traceId");
    }
  }
}
