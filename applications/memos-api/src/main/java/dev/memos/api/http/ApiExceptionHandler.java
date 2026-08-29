package dev.memos.api.http;

import dev.memos.governance.DeletionRequestException;
import dev.memos.ingestion.IngestionConflictException;
import dev.memos.materialization.TemporalMutationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(DataAccessException.class)
  ProblemDetail handleDatabaseUnavailable(
      DataAccessException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Ingestion unavailable",
        "INGESTION_UNAVAILABLE",
        "The durable store is temporarily unavailable.",
        request);
  }

  @ExceptionHandler(IngestionConflictException.class)
  ProblemDetail handleIngestionConflict(
      IngestionConflictException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "Ingestion conflict",
        exception.reason().name(),
        "The idempotency key or source ID is already bound to different immutable data.",
        request);
  }

  @ExceptionHandler(JobNotFoundException.class)
  ProblemDetail handleJobNotFound(JobNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Job not found",
        "JOB_NOT_FOUND",
        "The materialization job was not found.",
        request);
  }

  @ExceptionHandler(JobNotReplayableException.class)
  ProblemDetail handleJobNotReplayable(
      JobNotReplayableException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "Job not replayable",
        "JOB_NOT_REPLAYABLE",
        "The materialization job is not in a replayable state.",
        request);
  }

  @ExceptionHandler(MemoryNotFoundException.class)
  ProblemDetail handleMemoryNotFound(
      MemoryNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Memory not found",
        "MEMORY_NOT_FOUND",
        "The memory was not found.",
        request);
  }

  @ExceptionHandler(DeletionNotFoundException.class)
  ProblemDetail handleDeletionNotFound(
      DeletionNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Deletion operation not found",
        "DELETION_NOT_FOUND",
        "The deletion operation was not found.",
        request);
  }

  @ExceptionHandler(DeletionRequestException.class)
  ProblemDetail handleDeletionRequest(
      DeletionRequestException exception, HttpServletRequest request) {
    return switch (exception.failure()) {
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "Deletion target not found",
              "DELETION_TARGET_NOT_FOUND",
              "The deletion target was not found in the authenticated scope.",
              request);
      case IDEMPOTENCY_CONFLICT ->
          problem(
              HttpStatus.CONFLICT,
              "Deletion conflict",
              "DELETION_IDEMPOTENCY_CONFLICT",
              "The idempotency key is already bound to different immutable input.",
              request);
    };
  }

  @ExceptionHandler(TemporalMutationException.class)
  ProblemDetail handleTemporalMutation(
      TemporalMutationException exception, HttpServletRequest request) {
    return switch (exception.kind()) {
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "Memory not found",
              "MEMORY_NOT_FOUND",
              "The memory or scoped mutation evidence was not found.",
              request);
      case STALE_PRECONDITION ->
          problem(
              HttpStatus.PRECONDITION_FAILED,
              "Memory precondition failed",
              "STALE_MEMORY_VERSION",
              "The memory changed after the supplied entity tag.",
              request);
      case IDEMPOTENCY_CONFLICT ->
          problem(
              HttpStatus.CONFLICT,
              "Mutation conflict",
              "MUTATION_IDEMPOTENCY_CONFLICT",
              "The idempotency key is already bound to different immutable input.",
              request);
      case INVALID_TRANSITION ->
          problem(
              HttpStatus.CONFLICT,
              "Invalid memory transition",
              "INVALID_MEMORY_TRANSITION",
              "The requested memory transition is not allowed.",
              request);
    };
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail handleIllegalArgument(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "INVALID_REQUEST",
        "The request could not be validated.",
        request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail handleConstraintViolation(
      ConstraintViolationException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "INVALID_REQUEST",
        "The request failed validation.",
        request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ProblemDetail handleRequestBinding(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "INVALID_REQUEST",
        "The request could not be validated or decoded.",
        request);
  }

  private static ProblemDetail problem(
      HttpStatus status, String title, String code, String detailText, HttpServletRequest request) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, detailText);
    detail.setTitle(title);
    detail.setProperty("code", code);
    detail.setProperty("traceId", MDC.get(TraceIdFilter.TRACE_ID_KEY));
    detail.setInstance(java.net.URI.create(request.getRequestURI()));
    return detail;
  }
}
