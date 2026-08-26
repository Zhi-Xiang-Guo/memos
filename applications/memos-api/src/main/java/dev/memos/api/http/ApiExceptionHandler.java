package dev.memos.api.http;

import dev.memos.ingestion.IngestionConflictException;
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

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail handleIllegalArgument(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request",
        "INVALID_REQUEST",
        exception.getMessage(),
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
