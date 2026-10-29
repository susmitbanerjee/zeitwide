package com.settlecal.api.answers.app;

import com.settlecal.api.answers.domain.AnswerEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnswerProperties.class)
public class AnswersConfig {

  /** The one engine every surface answers through. */
  @Bean
  AnswerEngine answerEngine() {
    return new AnswerEngine();
  }
}
