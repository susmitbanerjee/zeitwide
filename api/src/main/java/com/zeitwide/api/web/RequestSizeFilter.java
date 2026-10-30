package com.zeitwide.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects oversized and unbounded streamed request bodies before MVC parses them. */
@Component
public final class RequestSizeFilter extends OncePerRequestFilter {

  private static final long MAX_REQUEST_BYTES = 64 * 1024;
  private final ObjectMapper json;

  public RequestSizeFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long length = request.getContentLengthLong();
    boolean streamedBody = request.getHeader("Transfer-Encoding") != null;
    if (length > MAX_REQUEST_BYTES) {
      write(response, Problems.of(ProblemCode.PAYLOAD_TOO_LARGE, "Maximum request size is 64 KiB"));
      return;
    }
    if (streamedBody && length < 0) {
      write(response, Problems.of(ProblemCode.LENGTH_REQUIRED, "A Content-Length is required"));
      return;
    }
    chain.doFilter(request, response);
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
