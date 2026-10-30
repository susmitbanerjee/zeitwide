package com.zeitwide.api.web;

import java.net.URI;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem details with a stable {@code code}. */
public final class Problems {

  public static final String CODE = "code";
  private static final String TYPE_BASE = "https://zeitwide.com/problems/";

  private Problems() {}

  /**
   * A problem for {@code code}. {@code detail} goes to the caller, so it must never carry secrets
   * or echo untrusted input at length.
   */
  public static ProblemDetail of(ProblemCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setTitle(code.title());
    problem.setType(URI.create(TYPE_BASE + code.code()));
    problem.setProperty(CODE, code.code());
    return problem;
  }
}
