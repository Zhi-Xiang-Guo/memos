package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.memos.materialization.TemporalMutationException;
import dev.memos.materialization.TemporalMutationFailureKind;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
  @Test
  void returnsRfc9457ProblemDetailsWithStableCode() {
    HttpServletRequest request = new MockHttpServletRequest("GET", "/v1/example");

    var detail =
        new ApiExceptionHandler()
            .handleIllegalArgument(new IllegalArgumentException("bad input"), request);

    assertThat(detail.getStatus()).isEqualTo(400);
    assertThat(detail.getProperties()).containsEntry("code", "INVALID_REQUEST");
    assertThat(detail.getDetail()).isEqualTo("The request could not be validated.");
    assertThat(detail.getDetail()).doesNotContain("bad input");
  }

  @Test
  void memoryNotFoundErrorIsContentSafe() {
    HttpServletRequest request = new MockHttpServletRequest("GET", "/v1/memories/unknown");

    var detail =
        new ApiExceptionHandler().handleMemoryNotFound(new MemoryNotFoundException(), request);

    assertThat(detail.getStatus()).isEqualTo(404);
    assertThat(detail.getProperties()).containsEntry("code", "MEMORY_NOT_FOUND");
    assertThat(detail.getDetail()).isEqualTo("The memory was not found.");
    assertThat(detail.getDetail()).doesNotContain("tenant", "user", "agent");
  }

  @Test
  void operatorAccessFailureIsForbiddenAndContentSafe() {
    HttpServletRequest request = new MockHttpServletRequest("POST", "/v1/retrieval/trace");

    var detail =
        new ApiExceptionHandler()
            .handleOperatorAccessDenied(new OperatorAccessDeniedException(), request);

    assertThat(detail.getStatus()).isEqualTo(403);
    assertThat(detail.getProperties()).containsEntry("code", "OPERATOR_ACCESS_DENIED");
    assertThat(detail.getDetail()).doesNotContain("key", "secret", "tenant");
  }

  @Test
  void temporalMutationFailuresHaveStableContentSafeStatusesAndCodes() {
    HttpServletRequest request = new MockHttpServletRequest("POST", "/v1/memories/id/corrections");
    Map<TemporalMutationFailureKind, Integer> statuses =
        Map.of(
            TemporalMutationFailureKind.NOT_FOUND, 404,
            TemporalMutationFailureKind.STALE_PRECONDITION, 412,
            TemporalMutationFailureKind.IDEMPOTENCY_CONFLICT, 409,
            TemporalMutationFailureKind.INVALID_TRANSITION, 409);
    Map<TemporalMutationFailureKind, String> codes =
        Map.of(
            TemporalMutationFailureKind.NOT_FOUND, "MEMORY_NOT_FOUND",
            TemporalMutationFailureKind.STALE_PRECONDITION, "STALE_MEMORY_VERSION",
            TemporalMutationFailureKind.IDEMPOTENCY_CONFLICT, "MUTATION_IDEMPOTENCY_CONFLICT",
            TemporalMutationFailureKind.INVALID_TRANSITION, "INVALID_MEMORY_TRANSITION");

    statuses.forEach(
        (kind, status) -> {
          var detail =
              new ApiExceptionHandler()
                  .handleTemporalMutation(new TemporalMutationException(kind), request);
          assertThat(detail.getStatus()).isEqualTo(status);
          assertThat(detail.getProperties()).containsEntry("code", codes.get(kind));
          assertThat(detail.getDetail()).doesNotContain("candidate", "sourceEvent", "secret");
        });
  }
}
