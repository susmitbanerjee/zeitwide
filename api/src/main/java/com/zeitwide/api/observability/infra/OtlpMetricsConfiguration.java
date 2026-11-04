package com.zeitwide.api.observability.infra;

import io.micrometer.registry.otlp.OtlpConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer's OTLP export, configured by the same {@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code
 * OTEL_EXPORTER_OTLP_HEADERS}, {@code OTEL_SERVICE_NAME} and {@code OTEL_RESOURCE_ATTRIBUTES}
 * variables as the OpenTelemetry agent, so one Secret Manager secret serves both. Spring's own
 * properties would ignore the headers variable.
 *
 * <p>Export is on only where {@code ZEITWIDE_OTLP_METRICS_ENABLED} is true (Cloud Run).
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsConfiguration {

  static final String INSTANCE_ID_ATTRIBUTE = "service.instance.id";

  @Bean
  OtlpConfig otlpConfig() {
    return new InstanceOtlpConfig(UUID.randomUUID().toString());
  }

  /**
   * Counters are cumulative per process, so each instance needs its own series: two Cloud Run
   * instances writing one series would look like constant counter resets.
   */
  record InstanceOtlpConfig(String instanceId) implements OtlpConfig {

    @Override
    public String get(String key) {
      return null;
    }

    /** Seconds, as the dashboards and alerts expect (http_server_requests_seconds_bucket). */
    @Override
    public TimeUnit baseTimeUnit() {
      return TimeUnit.SECONDS;
    }

    @Override
    public Map<String, String> resourceAttributes() {
      Map<String, String> attributes = new LinkedHashMap<>(OtlpConfig.super.resourceAttributes());
      attributes.putIfAbsent(INSTANCE_ID_ATTRIBUTE, instanceId);
      return Map.copyOf(attributes);
    }
  }
}
