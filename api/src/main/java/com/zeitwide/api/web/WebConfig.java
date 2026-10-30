package com.zeitwide.api.web;

import com.zeitwide.api.auth.api.EntitlementsInterceptor;
import com.zeitwide.api.auth.app.Entitlements;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

  private final Entitlements entitlements;

  WebConfig(Entitlements entitlements) {
    this.entitlements = entitlements;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new EntitlementsInterceptor(entitlements))
        .addPathPatterns("/v1/**", "/admin/**");
  }

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
