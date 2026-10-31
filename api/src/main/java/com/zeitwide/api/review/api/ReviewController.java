package com.zeitwide.api.review.api;

import com.zeitwide.api.review.app.ReviewService;
import com.zeitwide.api.review.domain.CandidateStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The reviewer's queue. The security chain admits only the Keycloak role {@code reviewer}. */
@Validated
@RestController
@RequestMapping("/v1/admin/candidates")
class ReviewController {

  private final ReviewService review;
  private final Clock clock;

  ReviewController(ReviewService review, Clock clock) {
    this.review = review;
    this.clock = clock;
  }

  @GetMapping
  ReviewDtos.CandidateList list(
      @RequestParam(defaultValue = "pending")
          @Pattern(regexp = "pending|approved|rejected|published")
          String status,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    LocalDate today = today();
    return new ReviewDtos.CandidateList(
        review.list(CandidateStatus.fromCode(status), limit).stream()
            .map(candidate -> ReviewDtos.CandidateView.from(candidate, today))
            .toList());
  }

  @GetMapping("/{id}")
  ReviewDtos.Detail get(@PathVariable @Min(1) long id) {
    return ReviewDtos.Detail.from(review.detail(id), today());
  }

  /** The reviewer is the token's subject, never a name the client sends. */
  @PostMapping("/{id}/decision")
  ReviewDtos.Detail decide(
      @PathVariable @Min(1) long id,
      @Valid @RequestBody ReviewDtos.Decision body,
      JwtAuthenticationToken reviewer) {
    return ReviewDtos.Detail.from(
        review.decide(id, body.verdict(), reviewer.getToken().getSubject(), body.reason()),
        today());
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
