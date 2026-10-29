package com.settlecal.api.web;

import com.settlecal.api.calendars.domain.CalendarId;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

  /** A malformed ID in a path fails conversion, which the advice reports as validation_failed. */
  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, CalendarId.class, CalendarId::new);
  }

  /** UTC, never the JVM's default time zone. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
