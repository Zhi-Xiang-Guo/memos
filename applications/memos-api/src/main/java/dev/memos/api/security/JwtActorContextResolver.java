package dev.memos.api.security;

import dev.memos.api.http.ScopeContextResolver;
import dev.memos.governance.MemoryScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class JwtActorContextResolver implements ScopeContextResolver, ActorContextResolver {
  @Override
  public MemoryScope resolve(HttpServletRequest request) {
    return resolveActor(request).scope();
  }

  @Override
  public AuthenticatedActor resolveActor(HttpServletRequest request) {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)) {
      throw new AuthenticationCredentialsNotFoundException("authenticated JWT is required");
    }
    var token = authentication.getToken();
    Set<String> roles =
        authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> authority.substring("ROLE_".length()))
            .collect(Collectors.toUnmodifiableSet());
    return new AuthenticatedActor(
        new MemoryScope(
            token.getClaimAsString("tenant_id"),
            token.getClaimAsString("user_id"),
            token.getClaimAsString("agent_id")),
        token.getSubject(),
        roles);
  }
}
