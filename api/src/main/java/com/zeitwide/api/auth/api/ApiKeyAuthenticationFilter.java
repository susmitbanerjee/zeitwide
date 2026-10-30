package com.zeitwide.api.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.auth.app.ApiKeyAuthenticator;
import com.zeitwide.api.mcp.app.McpProperties;
import com.zeitwide.api.web.ProblemCode;
import com.zeitwide.api.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Recognizes the API-key form of Bearer authentication before OAuth JWT processing. */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";
  private final ApiKeyAuthenticator authenticator;
  private final ObjectMapper json;
  private final McpProperties mcpProperties;

  public ApiKeyAuthenticationFilter(
      ApiKeyAuthenticator authenticator, ObjectMapper json, McpProperties mcpProperties) {
    this.authenticator = authenticator;
    this.json = json;
    this.mcpProperties = mcpProperties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith(BEARER)) {
      chain.doFilter(request, response);
      return;
    }
    String token = authorization.substring(BEARER.length()).trim();
    if (!token.startsWith("sc_")) {
      chain.doFilter(request, response);
      return;
    }
    var authenticated = authenticator.authenticate(token);
    if (authenticated.isEmpty()) {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      if (path.equals("/mcp")) {
        response.setHeader(
            HttpHeaders.WWW_AUTHENTICATE,
            "Bearer resource_metadata=\"" + mcpProperties.protectedResourceMetadataUrl() + "\"");
      }
      var problem = Problems.of(ProblemCode.INVALID_API_KEY, "The API key is not valid");
      response.setStatus(problem.getStatus());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      json.writeValue(response.getOutputStream(), problem);
      return;
    }
    var key = authenticated.get();
    var principal =
        new com.zeitwide.api.auth.domain.AccountPrincipal(key.accountId(), null, key.apiKeyId());
    var authentication = new AccountAuthenticationToken(principal, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    chain.doFilter(request, response);
  }
}
