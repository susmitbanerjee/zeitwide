package com.zeitwide.api.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class InternalSchedulerTokenFilterTest {

  private static final String AUDIENCE = "zeitwide-dev-outbox-drain";
  private static final String EMAIL = "zeitwide-sched-drain-dev@example.iam.gserviceaccount.com";

  @Test
  void permitsOnlyTheConfiguredGoogleSchedulerIdentity() throws Exception {
    JwtDecoder decoder = org.mockito.Mockito.mock(JwtDecoder.class);
    given(decoder.decode("signed-google-token")).willReturn(googleToken(AUDIENCE, EMAIL, true));
    InternalSchedulerTokenFilter filter =
        new InternalSchedulerTokenFilter(decoder, AUDIENCE, EMAIL, new ObjectMapper());
    MockHttpServletRequest request = request();
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(authority -> authority.getAuthority())
        .containsExactly("ROLE_SCHEDULER");
    SecurityContextHolder.clearContext();
  }

  @Test
  void rejectsWrongAudienceEmailOrUnverifiedEmail() throws Exception {
    assertRejected(googleToken("other-audience", EMAIL, true));
    assertRejected(googleToken(AUDIENCE, "other@example.com", true));
    assertRejected(googleToken(AUDIENCE, EMAIL, false));
    assertRejected(
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("https://attacker.example")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .audience(List.of(AUDIENCE))
            .claim("email", EMAIL)
            .claim("email_verified", true)
            .build());
  }

  private static void assertRejected(Jwt token) throws Exception {
    JwtDecoder decoder = org.mockito.Mockito.mock(JwtDecoder.class);
    given(decoder.decode("signed-google-token")).willReturn(token);
    var filter = new InternalSchedulerTokenFilter(decoder, AUDIENCE, EMAIL, new ObjectMapper());
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request(), response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull();
    SecurityContextHolder.clearContext();
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/test");
    request.addHeader("Authorization", "Bearer signed-google-token");
    return request;
  }

  private static Jwt googleToken(String audience, String email, boolean verified) {
    Instant now = Instant.now();
    return Jwt.withTokenValue("signed-google-token")
        .header("alg", "RS256")
        .issuer("https://accounts.google.com")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .audience(List.of(audience))
        .claim("email", email)
        .claim("email_verified", verified)
        .build();
  }
}
