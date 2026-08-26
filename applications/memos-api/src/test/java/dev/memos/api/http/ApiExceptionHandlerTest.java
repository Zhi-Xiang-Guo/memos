package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
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
    assertThat(detail.getDetail()).isEqualTo("bad input");
  }
}
