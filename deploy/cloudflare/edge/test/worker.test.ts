import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";

import { HTML, MARKDOWN, startWorker, type Edge } from "./harness";

const BROWSER =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
const BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
const GPTBOT =
  "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; GPTBot/1.3; +https://openai.com/gptbot)";
const CLAUDE_USER =
  "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Claude-User/1.0; +Claude-User@anthropic.com)";

let edge: Edge;

beforeAll(async () => {
  edge = await startWorker();
  await edge.seedRanges();
});
afterAll(() => edge.dispose());
beforeEach(() => {
  edge.recorded.origin.length = 0;
  edge.recorded.points.length = 0;
});

describe("content negotiation", () => {
  it("serves the Markdown twin when Accept prefers text/markdown", async () => {
    const { response, body, labels } = await edge.fetch("/calendars/GB-CHAPS", {
      headers: { accept: "text/markdown, text/html;q=0.5", "user-agent": "my-agent" },
    });
    expect(edge.recorded.origin).toEqual(["/calendars/GB-CHAPS.md"]);
    expect(body).toBe(MARKDOWN);
    expect(response.headers.get("content-type")).toContain("text/markdown");
    expect(response.headers.get("vary")).toBe("Accept");
    expect(labels).toContain("markdown=true");
  });

  it("keeps the query string and drops a trailing slash", async () => {
    await edge.fetch("/pricing/?plan=team", { headers: { accept: "text/markdown" } });
    expect(edge.recorded.origin).toEqual(["/pricing.md?plan=team"]);
  });

  it("passes browsers through and adds Vary: Accept to HTML", async () => {
    const { response, body, labels } = await edge.fetch("/calendars/GB-CHAPS", {
      headers: { accept: BROWSER_ACCEPT, "user-agent": BROWSER },
    });
    expect(edge.recorded.origin).toEqual(["/calendars/GB-CHAPS"]);
    expect(body).toBe(HTML);
    expect(response.headers.get("vary")).toBe("rsc, Accept");
    expect(labels).toBe("family=human; vendor=-; verified=false; markdown=false");
  });

  it("never negotiates private or non-page paths, or methods other than GET", async () => {
    await edge.fetch("/app/keys", { headers: { accept: "text/markdown" } });
    await edge.fetch("/", { headers: { accept: "text/markdown" } });
    await edge.fetch("/calendars/GB-CHAPS", {
      method: "HEAD",
      headers: { accept: "text/markdown" },
    });
    expect(edge.recorded.origin).toEqual(["/app/keys", "/", "/calendars/GB-CHAPS"]);
  });

  it("leaves Vary alone on non-HTML responses", async () => {
    const { response } = await edge.fetch("/_next/static/chunk.js");
    expect(response.headers.get("vary")).toBeNull();
  });
});

describe("bot classification", () => {
  it("does not verify GPTBot from an IP outside OpenAI's ranges", async () => {
    const { labels } = await edge.fetch("/calendars/GB-CHAPS", {
      headers: { "user-agent": GPTBOT },
      ip: "203.0.113.9",
    });
    expect(labels).toBe("family=training; vendor=openai; verified=false; markdown=false");
  });

  it("verifies GPTBot from OpenAI's published range", async () => {
    const { labels } = await edge.fetch("/calendars/GB-CHAPS", {
      headers: { "user-agent": GPTBOT },
      ip: "20.171.206.44",
    });
    expect(labels).toBe("family=training; vendor=openai; verified=true; markdown=false");
  });

  it("verifies Anthropic fetchers over IPv6 too", async () => {
    const { labels } = await edge.fetch("/docs", {
      headers: { "user-agent": CLAUDE_USER, accept: "text/markdown" },
      ip: "2600:1f28:365:80b5::1",
    });
    expect(labels).toBe("family=user-fetch; vendor=anthropic; verified=true; markdown=true");
  });

  it("labels an unlisted client as an unknown bot", async () => {
    const { labels } = await edge.fetch("/sources", { headers: { "user-agent": "curl/8.9.1" } });
    expect(labels).toBe("family=unknown; vendor=unknown; verified=false; markdown=false");
  });
});

describe("telemetry", () => {
  it("writes one data point per request, without the IP, User-Agent, path or query", async () => {
    await edge.fetch("/calendars/GB-CHAPS/2026-12-28?utm_source=x&email=a@b.example", {
      headers: { "user-agent": GPTBOT, accept: "text/markdown" },
      ip: "20.171.206.44",
      country: "US",
    });
    const [point] = await edge.points(1);
    expect(point).toEqual({
      indexes: ["training"],
      blobs: [
        "/calendars/:id/:date",
        "200",
        "NONE",
        "training",
        "openai",
        "1",
        "1",
        "US",
        expect.any(String),
      ],
      doubles: [new TextEncoder().encode(MARKDOWN).byteLength, 200],
    });
    const stored = JSON.stringify(point);
    for (const secret of ["20.171.206.44", "GPTBot/1.3", "GB-CHAPS", "utm_source", "a@b.example"]) {
      expect(stored).not.toContain(secret);
    }
  });

  it("counts the bytes of an HTML page and keeps its cache status", async () => {
    await edge.fetch("/calendars/GB-CHAPS", { headers: { "user-agent": BROWSER } });
    const [point] = await edge.points(1);
    expect(point?.blobs.slice(0, 7)).toEqual([
      "/calendars/:id",
      "200",
      "DYNAMIC",
      "human",
      "",
      "0",
      "0",
    ]);
    expect(point?.doubles).toEqual([new TextEncoder().encode(HTML).byteLength, 200]);
  });
});

describe("AI referral tagging", () => {
  it("sets zw_ref from an AI assistant's Referer", async () => {
    const { response } = await edge.fetch("/calendars/GB-CHAPS", {
      headers: { "user-agent": BROWSER, referer: "https://chatgpt.com/c/abc" },
    });
    expect(response.headers.get("set-cookie")).toBe(
      "zw_ref=ai:openai; Max-Age=2592000; Path=/; Secure; HttpOnly; SameSite=Lax",
    );
  });

  it("sets zw_ref from utm_source", async () => {
    const { response } = await edge.fetch("/docs?utm_source=perplexity", {
      headers: { "user-agent": BROWSER },
    });
    expect(response.headers.get("set-cookie")).toContain("zw_ref=ai:perplexity;");
  });

  it("sets nothing for other referrers, bots or non-HTML", async () => {
    const other = await edge.fetch("/docs", {
      headers: { "user-agent": BROWSER, referer: "https://news.example/" },
    });
    const bot = await edge.fetch("/docs", {
      headers: { "user-agent": GPTBOT, referer: "https://claude.ai/" },
    });
    const asset = await edge.fetch("/_next/static/a.js", {
      headers: { "user-agent": BROWSER, referer: "https://claude.ai/" },
    });
    expect(other.response.headers.get("set-cookie")).toBeNull();
    expect(bot.response.headers.get("set-cookie")).toBeNull();
    expect(asset.response.headers.get("set-cookie")).toBeNull();
  });
});

describe("rate limit", () => {
  it("answers unverified bots over the limit with 429 and Retry-After, never verified ones or people", async () => {
    const limited = await startWorker({ botLimit: 2 });
    try {
      await limited.seedRanges();
      const fake = { headers: { "user-agent": GPTBOT }, ip: "203.0.113.50" };
      const statuses = [];
      for (let i = 0; i < 4; i++) {
        statuses.push((await limited.fetch("/sources", fake)).response.status);
      }
      expect(statuses).toEqual([200, 200, 429, 429]);
      const last = await limited.fetch("/sources", fake);
      expect(last.response.headers.get("retry-after")).toBe("60");

      const real = { headers: { "user-agent": GPTBOT }, ip: "20.171.206.10" };
      const person = { headers: { "user-agent": BROWSER }, ip: "203.0.113.51" };
      for (let i = 0; i < 4; i++) {
        expect((await limited.fetch("/sources", real)).response.status).toBe(200);
        expect((await limited.fetch("/sources", person)).response.status).toBe(200);
      }
    } finally {
      await limited.dispose();
    }
  });
});

describe("cron triggers", () => {
  it("stores each vendor's ranges in KV and keeps the old list when a fetch fails", async () => {
    const kv = await edge.mf.getKVNamespace("RANGES");
    const stored = await kv.get("ranges:v1:openai-gptbot", "json");
    expect(stored).toMatchObject({
      prefixes: ["20.171.206.0/24"],
      createdAt: "2026-11-01T00:00:00",
    });
    expect(await kv.get("ranges:v1:apple")).toBeNull();
  });

  it("sends yesterday's counts to Loki", async () => {
    const worker = await edge.mf.getWorker();
    await worker.scheduled({ cron: "30 0 * * *", scheduledTime: new Date("2026-11-04T00:30:00Z") });
    expect(edge.recorded.sql.at(-1)).toContain(
      "timestamp >= toDateTime('2026-11-03 00:00:00') AND timestamp < toDateTime('2026-11-04 00:00:00')",
    );
    expect(edge.recorded.loki.at(-1)).toEqual({
      streams: [
        {
          stream: { service_name: "zeitwide-edge", env: "test", kind: "daily", family: "training" },
          values: [
            [
              "1793707200000000000",
              JSON.stringify({
                day: "2026-11-03",
                template: "/calendars/:id",
                status: "200",
                family: "training",
                vendor: "openai",
                verified: true,
                markdown: false,
                requests: 12,
                bytes: 3400,
              }),
            ],
          ],
        },
        expect.objectContaining({ stream: expect.objectContaining({ family: "human" }) }),
      ],
    });
  });
});
