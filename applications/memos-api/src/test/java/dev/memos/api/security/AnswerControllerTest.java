package dev.memos.api.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.memos.adapters.answering.FakeAnswerModelAdapter;
import dev.memos.adapters.spring.AnsweringProperties;
import dev.memos.answering.model.AnswerEvidence;
import dev.memos.answering.service.RagAnswerService;
import dev.memos.api.answering.AnswerController;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AnswerController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
  SecurityConfiguration.class,
  SecurityProblemWriter.class,
  JwtActorContextResolver.class,
  AnswerControllerTest.Config.class
})
@TestPropertySource(
    properties = {
      "memos.security.issuer=memos-test",
      "memos.security.audience=memos-api",
      "memos.security.hmac-secret=memos-test-signing-secret-with-at-least-32-bytes"
    })
class AnswerControllerTest {
  @Autowired MockMvc mvc;

  @Test
  void rejectsMissingTokenAndInvalidQuestion() throws Exception {
    mvc.perform(
            post("/v1/answers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"theme\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/v1/answers")
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void capturesVerifiedScopeAndReturnsValidatedAnswer() throws Exception {
    mvc.perform(
            post("/v1/answers")
                .header("Authorization", "Bearer " + token())
                .header("X-Tenant-Id", "victim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"theme\",\"toolCalling\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.abstain").value(true));
  }

  @Test
  void authenticatedSseCompletesWithFinalAnswerAndDone() throws Exception {
    var result =
        mvc.perform(
                post("/v1/answers/stream")
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"theme\",\"toolCalling\":true}"))
            .andExpect(request().asyncStarted())
            .andReturn();
    result.getAsyncResult(3000);
    String content =
        mvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String block : content.split("\n\n")) {
      for (String line : block.split("\n")) {
        if (line.startsWith("data:"))
          assertThat(
                  tools.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .readValue(line.substring(5), Object.class))
              .isInstanceOf(Map.class);
      }
    }
    assertThat(content)
        .contains("event:tool", "event:evidence", "event:delta", "event:answer", "event:done")
        .doesNotContain("event:error");
  }

  @TestConfiguration
  static class Config {
    @Bean
    AnsweringProperties answeringProperties() {
      return new AnsweringProperties(
          "fake", null, null, Duration.ofSeconds(3), Duration.ofSeconds(1), 1, 2);
    }

    @Bean
    RagAnswerService ragAnswerService() {
      UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
      return new RagAnswerService(
          (command, query) -> {
            assertThat(command.scope().tenantId()).isEqualTo("tenant-a");
            return new AnswerEvidence("evidence", List.of(id), List.of(id), "DISABLED");
          },
          new FakeAnswerModelAdapter(),
          Clock.systemUTC(),
          Duration.ofSeconds(3));
    }
  }

  private static String token() throws Exception {
    String header = encode("{\"alg\":\"HS256\"}");
    String payload =
        encode(
            "{\"iss\":\"memos-test\",\"aud\":[\"memos-api\"],\"sub\":\"subject-a\",\"tenant_id\":\"tenant-a\",\"user_id\":\"user-a\",\"agent_id\":\"agent-a\",\"roles\":[\"USER\"],\"exp\":"
                + (Instant.now().getEpochSecond() + 300)
                + "}");
    String input = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            "memos-test-signing-secret-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
    return input
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
  }

  private static String encode(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
}
