package com.settlecal.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the SettleCal serving layer: REST API, hosted MCP server and webhooks. */
@SpringBootApplication
public class SettleCalApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(SettleCalApiApplication.class, args);
  }
}
