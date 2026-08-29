package dev.memos.api.security;

import dev.memos.api.http.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class SecurityProblemWriter {
  private final ObjectMapper mapper;

  SecurityProblemWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String title,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status);
    body.put("detail", detail);
    body.put("instance", request.getRequestURI());
    body.put("code", code);
    body.put("traceId", MDC.get(TraceIdFilter.TRACE_ID_KEY));
    mapper.writeValue(response.getOutputStream(), body);
  }
}
