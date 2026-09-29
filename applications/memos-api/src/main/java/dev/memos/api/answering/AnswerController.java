package dev.memos.api.answering;

import dev.memos.adapters.spring.AnsweringProperties;
import dev.memos.answering.model.Answer;
import dev.memos.answering.model.AnswerCommand;
import dev.memos.answering.model.AnswerFailure;
import dev.memos.answering.service.RagAnswerService;
import dev.memos.api.security.ActorContextResolver;
import dev.memos.domain.temporal.LineageScope;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Thin HTTP adapter: capture verified scope before leaving the request thread. */
@RestController
@RequestMapping("/v1/answers")
public final class AnswerController {
  private final RagAnswerService answers;
  private final ActorContextResolver actors;
  private final AnsweringProperties properties;
  private final AnswerExecution execution;

  public AnswerController(
      RagAnswerService answers, ActorContextResolver actors, AnsweringProperties properties) {
    this.answers = answers;
    this.actors = actors;
    this.properties = properties;
    this.execution = new AnswerExecution(properties.maxConcurrent());
  }

  public record Request(
      @NotBlank @Size(max = 4096) String question,
      Boolean toolCalling,
      Boolean rerank,
      Boolean vectorOnly,
      @Min(1) @Max(20) Integer limit,
      @Min(64) @Max(8192) Integer maxTokens) {}

  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public Answer answer(@Valid @RequestBody Request body, HttpServletRequest request) {
    AnswerCommand command = command(body, request);
    var task = execution.prepare(() -> answers.answer(command, event -> {}));
    execution.start(task);
    try {
      return task.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException failure) {
      throw new AnswerFailure("ANSWER_TIMEOUT", false);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AnswerFailure("CANCELLED", false);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof AnswerFailure known) throw known;
      throw new AnswerFailure("ANSWER_UNAVAILABLE", false);
    } finally {
      task.cancel(true);
    }
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@Valid @RequestBody Request body, HttpServletRequest request) {
    AnswerCommand command = command(body, request);
    SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
    FutureTask<Void> task =
        execution.prepare(
            () -> {
              try {
                answers.answer(
                    command,
                    event -> {
                      try {
                        emitter.send(
                            SseEmitter.event()
                                .name(event.type())
                                .data(event.data(), MediaType.APPLICATION_JSON));
                      } catch (IOException | IllegalStateException failure) {
                        throw new AnswerFailure("CLIENT_DISCONNECTED", false);
                      }
                    });
                emitter.send(SseEmitter.event().name("done").data(Map.of("status", "complete")));
                emitter.complete();
              } catch (RuntimeException | IOException failure) {
                String code =
                    failure instanceof AnswerFailure known ? known.code() : "ANSWER_UNAVAILABLE";
                try {
                  emitter.send(SseEmitter.event().name("error").data(Map.of("code", code)));
                } catch (IOException | IllegalStateException ignored) {
                  /* disconnected clients receive no terminal event */
                }
                emitter.complete();
              }
              return null;
            });
    emitter.onTimeout(() -> task.cancel(true));
    emitter.onError(ignored -> task.cancel(true));
    emitter.onCompletion(() -> task.cancel(true));
    execution.start(task);
    return emitter;
  }

  private AnswerCommand command(Request body, HttpServletRequest request) {
    var scope = actors.resolveActor(request).scope();
    return new AnswerCommand(
        new LineageScope(scope.tenantId(), scope.userId(), scope.agentId()),
        body.question(),
        Boolean.TRUE.equals(body.toolCalling()),
        Boolean.TRUE.equals(body.rerank()),
        Boolean.TRUE.equals(body.vectorOnly()),
        body.limit() == null ? 8 : body.limit(),
        body.maxTokens() == null ? 1200 : body.maxTokens());
  }

  @ExceptionHandler(AnswerFailure.class)
  public ProblemDetail failure(AnswerFailure failure) {
    HttpStatus status =
        "ANSWER_BUSY".equals(failure.code())
            ? HttpStatus.TOO_MANY_REQUESTS
            : failure.code().contains("TIMEOUT")
                ? HttpStatus.GATEWAY_TIMEOUT
                : HttpStatus.BAD_GATEWAY;
    ProblemDetail detail =
        ProblemDetail.forStatusAndDetail(status, "The answer could not be completed.");
    detail.setProperty("code", failure.code());
    return detail;
  }

  @PreDestroy
  public void close() {
    execution.close();
  }
}
