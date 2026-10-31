package com.zeitwide.api.alerts.api;

import com.zeitwide.api.alerts.app.AlertRuleService;
import com.zeitwide.api.alerts.domain.AlertRule;
import com.zeitwide.api.alerts.domain.AlertRuleSettings;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.UniqueElements;

final class AlertRuleDtos {

  static final String CALENDAR_ID = "[A-Z0-9]+(-[A-Z0-9]+)*";
  static final String EVENT_TYPE = "closure_added|closure_removed|closure_changed|basis_changed";

  private AlertRuleDtos() {}

  record Configuration(
      @NotBlank @Size(max = AlertRuleSettings.MAX_NAME_LENGTH) String name,
      @NotNull @Size(min = 1, max = AlertRuleSettings.MAX_CALENDARS) @UniqueElements
          List<@NotNull @Size(max = 64) @Pattern(regexp = CALENDAR_ID) String> calendars,
      @NotNull @Size(min = 1, max = 4) @UniqueElements
          List<@NotNull @Size(max = 32) @Pattern(regexp = EVENT_TYPE) String> events,
      @Min(0) @Max(AlertRuleSettings.MAX_NOTICE_DAYS) Integer maxNoticeDays) {

    AlertRuleService.Request request() {
      return new AlertRuleService.Request(name, calendars, events, maxNoticeDays);
    }
  }

  record Rule(
      UUID id,
      String name,
      List<String> calendars,
      List<String> events,
      Integer maxNoticeDays,
      Instant createdAt) {

    static Rule from(AlertRule rule) {
      AlertRuleSettings settings = rule.settings();
      return new Rule(
          rule.id(),
          settings.name(),
          settings.calendars().stream().map(CalendarId::value).toList(),
          settings.events().stream().map(ChangeType::code).toList(),
          settings.maxNoticeDays(),
          rule.createdAt());
    }
  }
}
