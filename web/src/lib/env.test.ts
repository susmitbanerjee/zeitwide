import { describe, expect, it } from "vitest";

import { readServerEnv } from "./env";

describe("readServerEnv", () => {
  it("defaults to the local stack", () => {
    expect(readServerEnv({})).toEqual({
      apiUrl: "http://localhost:8080",
      apiKey: undefined,
      siteUrl: "http://localhost:3000",
      appUrl: "http://localhost:3000",
      mcpUrl: "http://localhost:8080/mcp",
    });
  });

  it("keeps origins only and separates the app host", () => {
    const env = readServerEnv({
      ZEITWIDE_API_URL: "https://api.zeitwide.com/",
      ZEITWIDE_API_KEY: "  zw_live_x  ",
      SITE_URL: "https://zeitwide.com/somewhere",
      APP_URL: "https://app.zeitwide.com",
      ZEITWIDE_MCP_URL: "https://mcp.zeitwide.com/mcp",
    });
    expect(env).toMatchObject({
      apiUrl: "https://api.zeitwide.com",
      apiKey: "zw_live_x",
      siteUrl: "https://zeitwide.com",
      appUrl: "https://app.zeitwide.com",
      mcpUrl: "https://mcp.zeitwide.com/mcp",
    });
    expect(readServerEnv({ ZEITWIDE_API_KEY: " " }).apiKey).toBeUndefined();
  });

  it("fails fast on a bad URL", () => {
    expect(() => readServerEnv({ SITE_URL: "zeitwide" })).toThrow(
      "SITE_URL must be an absolute URL",
    );
    expect(() => readServerEnv({ APP_URL: "ftp://x" })).toThrow("APP_URL must use http or https");
  });
});
