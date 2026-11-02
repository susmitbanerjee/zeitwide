import { afterEach, describe, expect, it, vi } from "vitest";

import { SITE_FILE_CACHE_CONTROL, serveSiteFile } from "./site-files";

// Outside a request there is nothing to wait for.
vi.mock("next/server", () => ({ connection: () => Promise.resolve() }));

describe("site files from the release bucket", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("passes a file through with its type, length and an hour of caching", async () => {
    vi.stubEnv("SITE_FILES_URL", "https://storage.example/site/current/");
    const fetchMock = vi.fn().mockResolvedValue(new Response("# Zeitwide\n"));
    vi.stubGlobal("fetch", fetchMock);
    const response = await serveSiteFile("llms.txt");
    expect(fetchMock.mock.calls[0]?.[0]).toBe("https://storage.example/site/current/llms.txt");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("text/plain; charset=utf-8");
    expect(response.headers.get("content-length")).toBe("11");
    expect(response.headers.get("cache-control")).toBe(SITE_FILE_CACHE_CONTROL);
    expect(await response.text()).toBe("# Zeitwide\n");

    fetchMock.mockResolvedValue(new Response("<urlset/>"));
    const sitemap = await serveSiteFile("sitemaps/GB-CHAPS.xml");
    expect(sitemap.headers.get("content-type")).toBe("application/xml; charset=utf-8");
  });

  it("refuses names it never writes, and answers 404 when unconfigured or missing", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubEnv("SITE_FILES_URL", "");
    expect((await serveSiteFile("llms.txt")).status).toBe(404);
    vi.stubEnv("SITE_FILES_URL", "https://storage.example/site");
    for (const name of ["../secret.txt", "a/b/c.txt", "llms.json", ".hidden.txt"]) {
      expect((await serveSiteFile(name)).status, name).toBe(404);
    }
    expect(fetchMock).not.toHaveBeenCalled();
    expect((await serveSiteFile("llms.txt")).status).toBe(404);
    fetchMock.mockResolvedValue(new Response(null, { status: 500 }));
    const failed = await serveSiteFile("llms.txt");
    expect(failed.status).toBe(503);
    expect(failed.headers.get("retry-after")).toBe("60");
  });
});
