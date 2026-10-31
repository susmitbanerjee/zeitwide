package com.zeitwide.api.operations.api;

import com.zeitwide.api.operations.app.OperationsService;
import com.zeitwide.api.operations.domain.ReleaseSummary;
import com.zeitwide.api.operations.domain.SourceHealth;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Source health and releases. The security chain admits only the role {@code reviewer}. */
@Validated
@RestController
@RequestMapping("/v1/admin")
class OperationsController {

  private final OperationsService operations;

  OperationsController(OperationsService operations) {
    this.operations = operations;
  }

  @GetMapping("/sources")
  SourceList sources() {
    return new SourceList(operations.sources().stream().map(Source::from).toList());
  }

  @GetMapping("/releases")
  ReleaseList releases(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return new ReleaseList(operations.releases(limit).stream().map(Release::from).toList());
  }

  record SourceList(List<Source> sources) {}

  record Source(
      String source,
      String publisher,
      String url,
      String role,
      String schedule,
      boolean enabled,
      String health,
      int consecutiveFailures,
      Instant lastCheckedAt,
      Instant lastSuccessAt,
      Instant lastSnapshotAt,
      String lastSnapshotSha256,
      String lastError,
      Instant suspendedAt) {

    static Source from(SourceHealth source) {
      return new Source(
          source.source(),
          source.publisher(),
          source.url(),
          source.role(),
          source.schedule(),
          source.enabled(),
          source.status().code(),
          source.consecutiveFailures(),
          source.lastCheckedAt(),
          source.lastSuccessAt(),
          source.lastSnapshotAt(),
          source.lastSnapshotSha256(),
          source.lastError(),
          source.suspendedAt());
    }
  }

  record ReleaseList(List<Release> releases) {}

  record Release(
      String id,
      Instant asOf,
      Map<String, Long> calendarRevisions,
      String exportSha256,
      Instant createdAt) {

    static Release from(ReleaseSummary release) {
      return new Release(
          release.id(),
          release.asOf(),
          release.calendarRevisions(),
          release.exportSha256(),
          release.createdAt());
    }
  }
}
