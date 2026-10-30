package com.zeitwide.api.changes.api;

import com.zeitwide.api.changes.app.ChangeFeedService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.hibernate.validator.constraints.UniqueElements;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/changes")
class ChangeController {

  static final String CALENDAR_ID = "[A-Z0-9]+(-[A-Z0-9]+)*";
  static final String CHANGE_TYPE = "closure_added|closure_removed|closure_changed|basis_changed";
  private final ChangeFeedService changes;

  ChangeController(ChangeFeedService changes) {
    this.changes = changes;
  }

  @GetMapping
  ChangeDtos.Page list(
      @RequestParam(name = "calendars", required = false) @Size(max = 10) @UniqueElements
          List<@Size(max = 64) @Pattern(regexp = CALENDAR_ID) String> calendars,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
      @RequestParam(name = "types", required = false) @Size(max = 4) @UniqueElements
          List<@Size(max = 32) @Pattern(regexp = CHANGE_TYPE) String> types,
      @RequestParam(required = false) @Size(max = 256) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return ChangeDtos.page(
        changes.list(
            Objects.requireNonNullElse(calendars, List.of()),
            since.toInstant(),
            Objects.requireNonNullElse(types, List.of()),
            cursor,
            limit));
  }
}
