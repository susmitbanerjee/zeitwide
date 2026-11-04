package com.zeitwide.api.observability.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Adds the {@code severity} field Cloud Logging reads to the JSON log lines. The trace_id and
 * span_id fields come from the OpenTelemetry agent, which puts them in the MDC.
 */
public final class CloudLoggingSeverity
    implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

  @Override
  public void customize(JsonWriter.Members<ILoggingEvent> members) {
    members.add("severity", event -> severity(event.getLevel()));
  }

  static String severity(Level level) {
    return switch (level.toInt()) {
      case Level.ERROR_INT -> "ERROR";
      case Level.WARN_INT -> "WARNING";
      case Level.INFO_INT -> "INFO";
      default -> "DEBUG";
    };
  }
}
