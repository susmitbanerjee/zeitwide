import { afterEach, describe, expect, it, vi } from "vitest";

import { SITE_FILE_CACHE_CONTROL, serveSiteFile } from "./site-files";

// Outside a request there is nothing to wait for, and nothing to cache in.
vi.mock("next/server", () => ({ connection: () => Promise.resolve() }));
vi.mock("next/cache", () => ({ cacheLife: () => undefined, cacheTag: () => undefined }));

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
    expect(fetchMock.mock.calls[0]?.[1]?.headers).toEqual({});
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("text/plain; charset=utf-8");
    expect(response.headers.get("content-length")).toBe("11");
    expect(response.headers.get("cache-control")).toBe(SITE_FILE_CACHE_CONTROL);
    expect(await response.text()).toBe("# Zeitwide\n");

    fetchMock.mockResolvedValue(new Response("<urlset/>"));
    const sitemap = await serveSiteFile("sitemaps/GB-CHAPS.xml");
    expect(sitemap.headers.get("content-type")).toBe("application/xml; charset=utf-8");
  });

  it("reads the private bucket with the service account's token, fetched once", async () => {
    vi.stubEnv("SITE_FILES_URL", "https://storage.googleapis.com/site-files/current");
    vi.stubEnv("SITE_FILES_AUTH", "gcp-metadata");
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url) =>
      Promise.resolve(
        url.startsWith("http://metadata.google.internal/")
          ? Response.json({ access_token: "ya29.token", expires_in: 3599 })
          : new Response("User-agent: *\n"),
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    expect((await serveSiteFile("robots.txt")).status).toBe(200);
    expect((await serveSiteFile("llms.txt")).status).toBe(200);
    const tokenCalls = fetchMock.mock.calls.filter(([url]) => url.includes("metadata"));
    expect(tokenCalls).toHaveLength(1);
    expect(fetchMock.mock.calls.at(-1)?.[1]).toMatchObject({
      headers: { Authorization: "Bearer ya29.token" },
    });
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
  });

  it("answers 503 when the bucket or the token server fails", async () => {
    vi.stubEnv("SITE_FILES_URL", "https://storage.example/site");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 403 })));
    const failed = await serveSiteFile("llms.txt");
    expect(failed.status).toBe(503);
    expect(failed.headers.get("retry-after")).toBe("60");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(new Uint8Array(10 * 1024 * 1024 + 1))),
    );
    expect((await serveSiteFile("llms.txt")).status).toBe(503);
  });
});
