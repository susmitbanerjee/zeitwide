package com.zeitwide.api.mcp.api;

/** A tool argument did not meet its published JSON schema. */
public final class McpInputException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public McpInputException() {
    super("The tool arguments are invalid");
  }
}
