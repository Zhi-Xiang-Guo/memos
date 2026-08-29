package dev.memos.api.security;

import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface ActorContextResolver {
  AuthenticatedActor resolveActor(HttpServletRequest request);
}
