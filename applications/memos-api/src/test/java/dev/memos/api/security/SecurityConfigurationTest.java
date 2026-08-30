package dev.memos.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.memos.api.http.ScopeContextResolver;
import dev.memos.api.http.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityConfigurationTest.ProbeController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
  SecurityConfiguration.class,
  SecurityProblemWriter.class,
  JwtActorContextResolver.class,
  TraceIdFilter.class,
  SecurityConfigurationTest.ProbeController.class
})
@TestPropertySource(
    properties = {
      "memos.security.issuer=memos-test",
      "memos.security.audience=memos-api",
      "memos.security.hmac-secret=memos-test-signing-secret-with-at-least-32-bytes"
    })
class SecurityConfigurationTest {
  private static final String SECRET = "memos-test-signing-secret-with-at-least-32-bytes";

  @Autowired private MockMvc mvc;

  @Test
  void requiresABearerTokenAndIgnoresForgeableScopeHeaders() throws Exception {
    mvc.perform(
            get("/v1/security-probe")
                .header("X-Tenant-Id", "forged-tenant")
                .header("X-User-Id", "forged-user")
                .header("X-Agent-Id", "forged-agent"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void validatesSignatureAudienceAndRequiredClaims() throws Exception {
    mvc.perform(
            get("/v1/security-probe")
                .header("Authorization", "Bearer " + token(SECRET + "-wrong", "memos-api", "USER")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_BEARER_TOKEN"));

    mvc.perform(
            get("/v1/security-probe")
                .header("Authorization", "Bearer " + token(SECRET, "other-api", "USER")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_BEARER_TOKEN"));
  }

  @Test
  void derivesScopeFromVerifiedClaimsRatherThanHeaders() throws Exception {
    String content =
        mvc.perform(
                get("/v1/security-probe")
                    .header("Authorization", "Bearer " + token(SECRET, "memos-api", "USER"))
                    .header("X-Tenant-Id", "forged-tenant")
                    .header("X-User-Id", "forged-user")
                    .header("X-Agent-Id", "forged-agent"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(content).contains("tenant-a", "user-a", "agent-a");
    assertThat(content).doesNotContain("forged");
  }

  @Test
  void enforcesOperatorAndPrivacyAdministratorRoles() throws Exception {
    String user = token(SECRET, "memos-api", "USER");
    String operator = token(SECRET, "memos-api", "OPERATOR");
    String privacyAdmin = token(SECRET, "memos-api", "PRIVACY_ADMIN");
    mvc.perform(
            post("/v1/retrieval/trace")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            post("/v1/retrieval/trace")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk());

    mvc.perform(get("/v1/operations/storage").header("Authorization", "Bearer " + user))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/v1/operations/storage").header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk());

    mvc.perform(
            post("/v1/admin/deletions/users/victim")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/v1/admin/deletions/users/victim")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + privacyAdmin))
        .andExpect(status().isOk());

    mvc.perform(get("/v1/security-probe").header("Authorization", "Bearer " + operator))
        .andExpect(status().isForbidden());
    mvc.perform(get("/v1/security-probe").header("Authorization", "Bearer " + privacyAdmin))
        .andExpect(status().isForbidden());
  }

  private static String token(String secret, String audience, String role) throws Exception {
    long now = Instant.now().getEpochSecond();
    String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    String payload =
        encode(
            "{\"iss\":\"memos-test\",\"aud\":[\""
                + audience
                + "\"],\"sub\":\"subject-a\",\"tenant_id\":\"tenant-a\","
                + "\"user_id\":\"user-a\",\"agent_id\":\"agent-a\",\"roles\":[\""
                + role
                + "\"],\"iat\":"
                + now
                + ",\"exp\":"
                + (now + 300)
                + "}");
    String signingInput = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return signingInput
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  @RestController
  static class ProbeController {
    private final ScopeContextResolver scopes;

    ProbeController(ScopeContextResolver scopes) {
      this.scopes = scopes;
    }

    @GetMapping("/v1/security-probe")
    Map<String, String> scope(HttpServletRequest request) {
      var scope = scopes.resolve(request);
      return Map.of(
          "tenantId", scope.tenantId(),
          "userId", scope.userId(),
          "agentId", scope.agentId());
    }

    @PostMapping("/v1/retrieval/trace")
    void trace() {}

    @GetMapping("/v1/operations/storage")
    void storage() {}

    @PostMapping("/v1/admin/deletions/users/{userId}")
    void admin() {}
  }
}
