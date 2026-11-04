import { afterEach, describe, expect, it, vi } from "vitest";

import catalogue from "../src/bots.json";
import { classify } from "../src/bots";
import { dailySql, lokiPush, parseRows, pushDailyReport, yesterday } from "../src/daily-report";
import type { Env } from "../src/env";
import { contains, parseAddress, parseNetwork } from "../src/ip";
import { prefersMarkdown } from "../src/negotiate";
import { pathInfo } from "../src/paths";
import { forgetRanges, inRanges, parseVendorRanges, rangeKey, refreshRanges } from "../src/ranges";
import { aiReferral, referralCookie } from "../src/referral";
import { countBytes, toDataPoint } from "../src/telemetry";

describe("prefersMarkdown", () => {
  it.each([
    ["text/markdown", true],
    ["text/markdown, text/html;q=0.9", true],
    ["text/markdown, */*", true],
    ["text/markdown;q=0.9, */*;q=0.8", true],
    ["text/markdown, text/html", true],
    ["text/html, text/markdown", false],
    ["text/html, text/markdown;q=0.5", false],
    ["text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", false],
    ["*/*", false],
    ["text/*", false],
    ["text/markdown;q=0", false],
    ["text/markdown;q=abc", false],
    ["garbage, /", false],
    ["", false],
  ])("%s → %s", (accept, expected) => {
    expect(prefersMarkdown(accept)).toBe(expected);
  });

  it("is false without an Accept header", () => {
    expect(prefersMarkdown(null)).toBe(false);
  });
});

describe("classify", () => {
  it.each([
    [
      "Mozilla/5.0 (compatible; GPTBot/1.3; +https://openai.com/gptbot)",
      "training",
      "openai",
      "GPTBot",
    ],
    ["Mozilla/5.0 (compatible; OAI-SearchBot/1.3)", "search", "openai", "OAI-SearchBot"],
    ["Mozilla/5.0 (compatible; ChatGPT-User/1.0)", "user-fetch", "openai", "ChatGPT-User"],
    [
      "Mozilla/5.0 (compatible; ClaudeBot/1.0; +claudebot@anthropic.com)",
      "training",
      "anthropic",
      "ClaudeBot",
    ],
    ["Claude-SearchBot/1.0", "search", "anthropic", "Claude-SearchBot"],
    ["Mozilla/5.0 (compatible; PerplexityBot/1.0)", "search", "perplexity", "PerplexityBot"],
    ["Perplexity-User/1.0", "user-fetch", "perplexity", "Perplexity-User"],
    [
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
      "search",
      "google",
      "Googlebot",
    ],
    ["Googlebot-Image/1.0", "search", "google", "Googlebot"],
    ["Mozilla/5.0 (compatible; Google-Agent)", "agent", "google", "Google-Agent"],
    [
      "Mozilla/5.0 (Applebot/0.1; +http://www.apple.com/go/applebot)",
      "search",
      "apple",
      "Applebot",
    ],
    ["CCBot/2.0 (https://commoncrawl.org/faq/)", "training", "commoncrawl", "CCBot"],
    ["Mozilla/5.0 (compatible; UptimeRobot/2.0)", "monitor", "uptimerobot", "UptimeRobot"],
    ["python-requests/2.32", "unknown", "unknown", ""],
    ["Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15", "human", "", ""],
  ])("%s", (ua, family, vendor, token) => {
    expect(classify(ua)).toMatchObject({ family, vendor, token });
  });

  it("calls a missing or empty User-Agent an unknown bot", () => {
    expect(classify(null).family).toBe("unknown");
    expect(classify("  ").family).toBe("unknown");
  });

  it("verifies only the five vendors with published ranges", () => {
    expect(classify("GPTBot/1.3").ranges).toEqual(["openai-gptbot"]);
    expect(classify("bingbot/2.0").ranges).toEqual([]);
  });

  it("has a version, unique tokens and a known family for every bot", () => {
    expect(catalogue.version).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const tokens = catalogue.bots.map((bot) => bot.token.toLowerCase());
    expect(new Set(tokens).size).toBe(tokens.length);
  });

  it("lists every token after any token it contains, so the specific one wins", () => {
    const tokens = catalogue.bots.map((bot) => bot.token.toLowerCase());
    tokens.forEach((token, i) => {
      for (const earlier of tokens.slice(0, i)) {
        expect(token.includes(earlier), `${token} is shadowed by ${earlier}`).toBe(false);
      }
    });
  });
});

describe("ip", () => {
  it("parses IPv4, IPv6 and IPv4-mapped IPv6", () => {
    expect(parseAddress("20.171.206.44")).toEqual(Uint8Array.from([20, 171, 206, 44]));
    expect(parseAddress("::ffff:20.171.206.44")).toEqual(Uint8Array.from([20, 171, 206, 44]));
    expect(parseAddress("2001:db8::1")?.length).toBe(16);
    expect(parseAddress("fe80::1%eth0")?.length).toBe(16);
    expect(parseAddress("::")).toEqual(new Uint8Array(16));
  });

  it.each([
    "",
    "1.2.3",
    "1.2.3.256",
    "1:2:3:4:5:6:7:8:9",
    "1::2::3",
    "12345::",
    "1:2:3:4:5:6:7",
    "::1.2.3.999",
  ])("rejects %s", (text) => {
    expect(parseAddress(text)).toBeNull();
  });

  it("matches addresses inside a network, on byte and bit boundaries", () => {
    const v4 = parseNetwork("20.171.206.0/24");
    const odd = parseNetwork("216.73.216.0/22");
    const v6 = parseNetwork("2600:1f28:365:80b0::/60");
    expect(v4 && contains(v4, parseAddress("20.171.206.255")!)).toBe(true);
    expect(v4 && contains(v4, parseAddress("20.171.207.1")!)).toBe(false);
    expect(odd && contains(odd, parseAddress("216.73.219.9")!)).toBe(true);
    expect(odd && contains(odd, parseAddress("216.73.220.9")!)).toBe(false);
    expect(v6 && contains(v6, parseAddress("2600:1f28:365:80bf::9")!)).toBe(true);
    expect(v6 && contains(v6, parseAddress("20.171.206.1")!)).toBe(false);
    expect(parseNetwork("1.2.3.4")).toEqual({ bytes: Uint8Array.from([1, 2, 3, 4]), bits: 32 });
  });

  it.each(["1.2.3.0/33", "1.2.3.0/x", "nope/8", "::/129"])("rejects network %s", (cidr) => {
    expect(parseNetwork(cidr)).toBeNull();
  });
});

describe("pathInfo", () => {
  // The same cases as matchPublicPath in web/src/lib/public-routes.test.ts.
  it.each([
    ["/calendars", "/calendars"],
    ["/calendars/GB-CHAPS", "/calendars/:id"],
    ["/calendars/GB-CHAPS/2027", "/calendars/:id/:year"],
    ["/calendars/GB-CHAPS/2026-12-28", "/calendars/:id/:date"],
    ["/compare/EU-T2-vs-GB-CHAPS/2027", "/compare/:pair/:year"],
    ["/sources", "/sources"],
    ["/pricing/", "/pricing"],
    ["/docs", "/docs"],
    ["/docs/rules", "/docs/:slug"],
  ])("%s is the public page %s", (path, template) => {
    expect(pathInfo(path)).toEqual({ template, negotiable: true });
  });

  it.each([
    ["/", "/"],
    ["/docs/a/b", "other"],
    ["/calendars/GB-CHAPS/nope", "other"],
    ["/calendars/a/b/c", "other"],
    ["/compare/a-vs-b/x", "other"],
    ["/sources/x", "other"],
    ["/calendars/%E0%A4%A", "other"],
    ["/calendars/GB-CHAPS.md", "/calendars/:id.md"],
    ["/robots.txt", "/robots.txt"],
    ["/docs/llms-full.txt", "/docs/llms-full.txt"],
    ["/_next/static/chunks/a.js", "/_next/*"],
    ["/app/keys", "/app/*"],
    ["/sitemaps/GB-CHAPS.xml", "/sitemaps/*"],
  ])("%s is not negotiated (template %s)", (path, template) => {
    expect(pathInfo(path)).toEqual({ template, negotiable: false });
  });
});

describe("aiReferral", () => {
  const url = (query = "") => new URL(`https://zeitwide.example/docs${query}`);

  it.each([
    ["https://chatgpt.com/c/1", "openai"],
    ["https://www.perplexity.ai/search?q=x", "perplexity"],
    ["https://claude.ai/chat/1", "anthropic"],
    ["https://gemini.google.com/app", "google"],
    ["https://copilot.microsoft.com/", "microsoft"],
  ])("tags %s", (referer, vendor) => {
    expect(aiReferral(referer, url())).toBe(vendor);
  });

  it("reads utm_source when the Referer is missing or another site", () => {
    expect(aiReferral(null, url("?utm_source=chatgpt.com"))).toBe("openai");
    expect(aiReferral("https://news.example/", url("?utm_source=Claude"))).toBe("anthropic");
  });

  it("ignores look-alikes, bad Referers and other sources", () => {
    expect(aiReferral("https://notchatgpt.com/", url())).toBeNull();
    expect(aiReferral("https://google.com/", url())).toBeNull();
    expect(aiReferral("not a url", url("?utm_source=newsletter"))).toBeNull();
  });

  it("puts only the vendor in a 30-day cookie", () => {
    expect(referralCookie("openai")).toBe(
      "zw_ref=ai:openai; Max-Age=2592000; Path=/; Secure; HttpOnly; SameSite=Lax",
    );
  });
});

describe("telemetry", () => {
  const event = {
    template: "/docs",
    status: 200,
    cacheStatus: "HIT",
    family: "human" as const,
    vendor: "",
    verified: false,
    markdown: false,
    country: "IN",
    bytes: 10,
  };

  it("keeps the column order the report reads", () => {
    expect(toDataPoint(event)).toEqual({
      indexes: ["human"],
      blobs: ["/docs", "200", "HIT", "human", "", "0", "0", "IN", catalogue.version],
      doubles: [10, 200],
    });
  });

  it("uses Content-Length when there is one, and counts streamed bodies", async () => {
    const known = vi.fn();
    countBytes(new Response("abc", { headers: { "content-length": "3" } }), known);
    expect(known).toHaveBeenCalledWith(3);

    const empty = vi.fn();
    countBytes(new Response(null, { status: 204 }), empty);
    expect(empty).toHaveBeenCalledWith(0);

    const streamed = vi.fn();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(5));
        controller.enqueue(new Uint8Array(7));
        controller.close();
      },
    });
    const { response, finished } = countBytes(new Response(body), streamed);
    expect((await response.arrayBuffer()).byteLength).toBe(12);
    await finished;
    expect(streamed).toHaveBeenCalledWith(12);
  });
});

class MemoryKv {
  readonly values = new Map<string, string>();
  reads = 0;
  async get(key: string, options?: { type?: string }) {
    this.reads++;
    const value = this.values.get(key);
    return value === undefined ? null : options?.type === "json" ? JSON.parse(value) : value;
  }
  async put(key: string, value: string) {
    this.values.set(key, value);
  }
}

describe("ranges", () => {
  afterEach(() => {
    forgetRanges();
    vi.unstubAllGlobals();
  });

  it("reads vendor JSON and skips malformed prefixes", () => {
    expect(
      parseVendorRanges({
        creationTime: "2026-11-01",
        prefixes: [
          { ipv4Prefix: "1.2.3.0/24" },
          { ipv6Prefix: "2001:db8::/32" },
          { ipv4Prefix: "bad" },
          7,
        ],
      }),
    ).toEqual({ createdAt: "2026-11-01", prefixes: ["1.2.3.0/24", "2001:db8::/32"] });
    expect(() => parseVendorRanges({ prefixes: [] })).toThrow("no valid prefixes");
    expect(() => parseVendorRanges("<html>")).toThrow("no prefixes array");
  });

  it("checks an IP against stored lists and caches them per isolate for an hour", async () => {
    const kv = new MemoryKv();
    kv.values.set(rangeKey("a"), JSON.stringify({ prefixes: ["1.2.3.0/24", "junk"] }));
    const store = kv as unknown as KVNamespace;
    expect(await inRanges(store, ["missing", "a"], "1.2.3.4", 0)).toBe(true);
    expect(await inRanges(store, ["a"], "1.2.4.4", 1000)).toBe(false);
    expect(kv.reads).toBe(2);
    expect(await inRanges(store, ["a"], "1.2.3.4", 2 * 60 * 60 * 1000)).toBe(true);
    expect(kv.reads).toBe(3);
    expect(await inRanges(store, ["a"], null)).toBe(false);
    expect(await inRanges(store, [], "1.2.3.4")).toBe(false);
  });

  it("verifies nothing when KV fails or a list is missing, and asks again a minute later", async () => {
    const kv = new MemoryKv();
    const store = kv as unknown as KVNamespace;
    const get = vi.spyOn(kv, "get").mockRejectedValueOnce(new Error("KV GET failed: 429"));
    expect(await inRanges(store, ["a"], "1.2.3.4", 0)).toBe(false);
    expect(await inRanges(store, ["a"], "1.2.3.4", 30_000)).toBe(false);
    expect(get).toHaveBeenCalledTimes(1);
    kv.values.set(rangeKey("a"), JSON.stringify({ prefixes: ["1.2.3.0/24"] }));
    expect(await inRanges(store, ["a"], "1.2.3.4", 61_000)).toBe(true);
    expect(get).toHaveBeenCalledTimes(2);
  });

  it("stores what each vendor returns and reports what failed", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url === "https://ok.example/"
          ? Response.json({ prefixes: [{ ipv4Prefix: "1.2.3.0/24" }] })
          : new Response("down", { status: 503 }),
      ),
    );
    const kv = new MemoryKv();
    const results = await refreshRanges(
      kv as unknown as KVNamespace,
      new Date("2026-11-04T03:17:00Z"),
      {
        ok: "https://ok.example/",
        down: "https://down.example/",
      },
    );
    expect(results).toEqual([
      { id: "ok", ok: true, count: 1 },
      { id: "down", ok: false, error: "HTTP 503" },
    ]);
    expect(JSON.parse(kv.values.get(rangeKey("ok")) ?? "{}")).toEqual({
      source: "https://ok.example/",
      fetchedAt: "2026-11-04T03:17:00.000Z",
      createdAt: null,
      prefixes: ["1.2.3.0/24"],
    });
    expect(kv.values.has(rangeKey("down"))).toBe(false);
  });
});

describe("daily report", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("covers the whole UTC day before the run", () => {
    const { day, start, end } = yesterday(new Date("2026-03-01T00:30:00Z"));
    expect(day).toBe("2026-02-28");
    expect(start.toISOString()).toBe("2026-02-28T00:00:00.000Z");
    expect(end.toISOString()).toBe("2026-03-01T00:00:00.000Z");
    expect(dailySql(start, end)).toContain("FROM zeitwide_edge");
  });

  it("parses rows with string numbers and rejects a body without data", () => {
    expect(
      parseRows({
        data: [
          {
            template: "/",
            status: "200",
            family: "human",
            vendor: "",
            verified: "0",
            markdown: "1",
            requests: "4",
            bytes: "10.6",
          },
        ],
      }),
    ).toEqual([
      {
        template: "/",
        status: "200",
        family: "human",
        vendor: "",
        verified: false,
        markdown: true,
        requests: 4,
        bytes: 11,
      },
    ]);
    expect(() => parseRows({ errors: ["x"] })).toThrow("without a data array");
  });

  it("groups lines into one stream per family", () => {
    const row = {
      template: "/",
      status: "200",
      vendor: "",
      verified: false,
      markdown: false,
      requests: 1,
      bytes: 1,
    };
    const push = lokiPush(
      [
        { ...row, family: "human" },
        { ...row, family: "human", status: "404" },
        { ...row, family: "search" },
      ],
      "2026-11-03",
      "dev",
    ) as {
      streams: { stream: Record<string, string>; values: string[][] }[];
    };
    expect(push.streams.map((s) => [s.stream.family, s.values.length])).toEqual([
      ["human", 2],
      ["search", 1],
    ]);
  });

  const env = {
    ENVIRONMENT: "dev",
    CF_ACCOUNT_ID: "acc",
    CF_ANALYTICS_TOKEN: "t",
    LOKI_PUSH_URL: "https://loki.example/push",
    LOKI_USER: "1",
    LOKI_TOKEN: "k",
  } as unknown as Env;

  it("refuses to run without its settings", async () => {
    await expect(pushDailyReport({ ENVIRONMENT: "dev" } as Env, new Date())).rejects.toThrow(
      "set CF_ACCOUNT_ID",
    );
  });

  it("pushes nothing for an empty day, and fails loudly on HTTP errors", async () => {
    const fetchMock = vi.fn(async () => Response.json({ data: [] }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await pushDailyReport(env, new Date("2026-11-04T00:30:00Z"))).toEqual({
      day: "2026-11-03",
      rows: 0,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("no", { status: 401 })),
    );
    await expect(pushDailyReport(env, new Date())).rejects.toThrow("SQL API: HTTP 401");

    const rows = {
      data: [{ template: "/", status: "200", family: "human", requests: "1", bytes: "1" }],
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.includes("cloudflare") ? Response.json(rows) : new Response("", { status: 400 }),
      ),
    );
    await expect(pushDailyReport(env, new Date())).rejects.toThrow("Loki push: HTTP 400");
  });
});
