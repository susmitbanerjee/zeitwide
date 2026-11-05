import { afterEach, describe, expect, it, vi } from "vitest";

import {
  AGENT_DOCUMENT_CACHE_CONTROL,
  API_DOCUMENTS,
  type ApiDocument,
  preflight,
  serveApiDocument,
  serveOpenApi,
} from "./agent-documents";

vi.mock("server-only", () => ({}));
vi.mock("next/server", () => ({ connection: () => Promise.resolve() }));
vi.mock("next/cache", () => ({ cacheLife: () => undefined }));
vi.mock("@/lib/env", () => ({ env: { apiUrl: "https://api.example" } }));

describe("discovery documents on the website", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each(Object.entries(API_DOCUMENTS) as [ApiDocument, string][])(
    "passes %s through from the API with its type, open CORS and an hour of caching",
    async (path, type) => {
      const fetchMock = vi.fn().mockResolvedValue(Response.json({ name: "Zeitwide" }));
      vi.stubGlobal("fetch", fetchMock);
      const response = await serveApiDocument(path);
      expect(fetchMock.mock.calls[0]?.[0]).toBe(`https://api.example${path}`);
      expect(response.status).toBe(200);
      expect(response.headers.get("content-type")).toBe(type);
      expect(response.headers.get("access-control-allow-origin")).toBe("*");
      expect(response.headers.get("cache-control")).toBe(AGENT_DOCUMENT_CACHE_CONTROL);
      expect(await response.json()).toEqual({ name: "Zeitwide" });
    },
  );

  it("says unavailable, uncached, when the API fails or sends something that isn't JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("down", { status: 502 })));
    const failed = await serveApiDocument("/.well-known/agent-card.json");
    expect(failed.status).toBe(503);
    expect(failed.headers.get("retry-after")).toBe("60");

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>")));
    expect((await serveApiDocument("/.well-known/ai-catalog.json")).status).toBe(503);

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("fetch failed")));
    expect((await serveApiDocument("/.well-known/zeitwide-keys.json")).status).toBe(503);
  });

  it("serves the bundled OpenAPI document as JSON any origin may read", async () => {
    const response = serveOpenApi();
    expect(response.headers.get("content-type")).toBe(
      "application/vnd.oai.openapi+json; charset=utf-8",
    );
    expect(response.headers.get("access-control-allow-origin")).toBe("*");
    const spec = (await response.json()) as { openapi: string; paths: Record<string, unknown> };
    expect(spec.openapi).toBe("3.1.0");
    expect(Object.keys(spec.paths)).toContain("/v1/server-card");
  });

  it("answers CORS preflights", () => {
    const response = preflight();
    expect(response.status).toBe(204);
    expect(response.headers.get("access-control-allow-methods")).toContain("GET");
  });
});
