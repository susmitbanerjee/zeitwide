package com.zeitwide.otel;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * Wraps the agent's exporting span processors in {@link ErrorKeepingSpanProcessor}. Run the agent
 * with {@code OTEL_TRACES_SAMPLER=always_on} so every span reaches it.
 */
public final class TraceSamplingCustomizer implements AutoConfigurationCustomizerProvider {

  static final String RATIO_PROPERTY = "zeitwide.traces.sample.ratio";
  static final String MAX_PENDING_PROPERTY = "zeitwide.traces.max.pending";
  private static final double DEFAULT_RATIO = 0.1;
  private static final int DEFAULT_MAX_PENDING_TRACES = 2048;

  @Override
  public void customize(AutoConfigurationCustomizer customizer) {
    customizer.addSpanProcessorCustomizer(
        (processor, config) ->
            new ErrorKeepingSpanProcessor(
                processor,
                config.getDouble(RATIO_PROPERTY, DEFAULT_RATIO),
                config.getInt(MAX_PENDING_PROPERTY, DEFAULT_MAX_PENDING_TRACES)));
  }
}
