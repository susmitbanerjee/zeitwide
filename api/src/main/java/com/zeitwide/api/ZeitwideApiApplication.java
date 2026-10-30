package com.zeitwide.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Zeitwide serving layer: REST API, hosted MCP server and webhooks. */
@SpringBootApplication
public class ZeitwideApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ZeitwideApiApplication.class, args);
  }
}
