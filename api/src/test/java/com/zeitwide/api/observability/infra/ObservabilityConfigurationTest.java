package com.zeitwide.api.observability.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

class ObservabilityConfigurationTest {

  @Test
  void shouldGiveEachProcessItsOwnMetricSeries() {
    var first = new OtlpMetricsConfiguration().otlpConfig();
    var second = new OtlpMetricsConfiguration().otlpConfig();

    String firstId = first.resourceAttributes().get(OtlpMetricsConfiguration.INSTANCE_ID_ATTRIBUTE);
    assertThat(firstId).isNotBlank();
    assertThat(second.resourceAttributes())
        .containsKey(OtlpMetricsConfiguration.INSTANCE_ID_ATTRIBUTE)
        .doesNotContainEntry(OtlpMetricsConfiguration.INSTANCE_ID_ATTRIBUTE, firstId);
    assertThat(first.baseTimeUnit()).isEqualTo(java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  void shouldMapLogbackLevelsToCloudLoggingSeverities() {
    assertThat(CloudLoggingSeverity.severity(Level.ERROR)).isEqualTo("ERROR");
    assertThat(CloudLoggingSeverity.severity(Level.WARN)).isEqualTo("WARNING");
    assertThat(CloudLoggingSeverity.severity(Level.INFO)).isEqualTo("INFO");
    assertThat(CloudLoggingSeverity.severity(Level.TRACE)).isEqualTo("DEBUG");
  }
}
