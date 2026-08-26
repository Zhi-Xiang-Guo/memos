package dev.memos.api.http;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail handleIllegalArgument(
      IllegalArgumentException exception, HttpServletRequest request) {
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    detail.setTitle("Invalid request");
    detail.setProperty("code", "INVALID_REQUEST");
    detail.setProperty("traceId", MDC.get(TraceIdFilter.TRACE_ID_KEY));
    detail.setInstance(java.net.URI.create(request.getRequestURI()));
    return detail;
  }
}
