package dev.memos.api.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
  private static final Pattern SAFE_IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/\\-]{0,199}");

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, SecurityProblemWriter problems, JwtAuthenticationConverter converter)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/livez", "/readyz", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/retrieval/trace")
                    .hasRole(MemosRoles.OPERATOR)
                    .requestMatchers(HttpMethod.GET, "/v1/operations/storage")
                    .hasRole(MemosRoles.OPERATOR)
                    .requestMatchers("/v1/admin/**")
                    .hasRole(MemosRoles.PRIVACY_ADMIN)
                    .requestMatchers("/actuator/**")
                    .hasRole(MemosRoles.OPERATOR)
                    .requestMatchers("/v1/**")
                    .hasRole(MemosRoles.USER)
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "Authentication required",
                                "AUTHENTICATION_REQUIRED",
                                "A valid bearer token is required."))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "Access denied",
                                "ACCESS_DENIED",
                                "The authenticated subject is not authorized for this operation.")))
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "Authentication required",
                                "INVALID_BEARER_TOKEN",
                                "The bearer token is missing, invalid, or expired.")))
        .httpBasic(AbstractHttpConfigurer::disable);
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties) {
    var key =
        new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.issuer());
    OAuth2TokenValidator<Jwt> claims = jwt -> validateClaims(jwt, properties.audience());
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, claims));
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    converter.setPrincipalClaimName("sub");
    return converter;
  }

  private static OAuth2TokenValidatorResult validateClaims(Jwt jwt, String audience) {
    if (!jwt.getAudience().contains(audience)
        || !validIdentifier(jwt.getSubject(), 200)
        || !validIdentifier(jwt.getClaimAsString("tenant_id"), 128)
        || !validIdentifier(jwt.getClaimAsString("user_id"), 128)
        || !validIdentifier(jwt.getClaimAsString("agent_id"), 128)
        || !validRoles(jwt.getClaim("roles"))) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error(
              "invalid_token", "required audience, scope, or role claims are invalid", null));
    }
    return OAuth2TokenValidatorResult.success();
  }

  private static boolean validIdentifier(String value, int maxLength) {
    return value != null && value.length() <= maxLength && SAFE_IDENTIFIER.matcher(value).matches();
  }

  private static boolean validRoles(Object value) {
    if (!(value instanceof Collection<?> roles) || roles.isEmpty()) {
      return false;
    }
    return roles.stream()
        .allMatch(role -> role instanceof String text && MemosRoles.ALLOWED.contains(text));
  }
}
