package dev.memos.api.http;

import dev.memos.ingestion.ActorType;
import dev.memos.ingestion.IngestionDisposition;
import dev.memos.ingestion.SourceIngestionCommand;
import dev.memos.ingestion.SourceIngestionService;
import dev.memos.ingestion.SourceType;
import dev.memos.ingestion.TrustLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/source-events")
public final class SourceIngestionController {
  private final SourceIngestionService service;
  private final ScopeContextResolver scopeResolver;
  private final ObjectMapper mapper;

  public SourceIngestionController(
      SourceIngestionService service, ScopeContextResolver scopeResolver, ObjectMapper mapper) {
    this.service = service;
    this.scopeResolver = scopeResolver;
    this.mapper = mapper;
  }

  @PostMapping
  ResponseEntity<SourceEventReceiptResponse> accept(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody SourceEventRequest body,
      HttpServletRequest request) {
    var command =
        new SourceIngestionCommand(
            scopeResolver.resolve(request),
            body.sourceId(),
            body.sessionId(),
            idempotencyKey,
            enumValue(ActorType.class, body.actorType(), "actorType"),
            enumValue(SourceType.class, body.sourceType(), "sourceType"),
            enumValue(TrustLevel.class, body.trustLevel(), "trustLevel"),
            body.occurredAt(),
            payloadJson(body),
            MDC.get(TraceIdFilter.TRACE_ID_KEY));
    var receipt = service.ingest(command);
    var response =
        new SourceEventReceiptResponse(
            receipt.sourceEventId().toString(),
            receipt.sourceId(),
            receipt.materializationJobId().toString(),
            receipt.disposition().name(),
            receipt.acceptedAt(),
            "PENDING");
    HttpStatus status =
        receipt.disposition() == IngestionDisposition.ACCEPTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(response);
  }

  private String payloadJson(SourceEventRequest body) {
    try {
      return mapper.writeValueAsString(body.payload());
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("payload must be valid JSON", exception);
    }
  }

  private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " has an unsupported value", exception);
    }
  }
}
