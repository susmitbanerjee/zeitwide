import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * Every public data page has a Markdown twin with the same facts. Walks every URL in the sitemap,
 * fetches both, and checks the facts, the size and the headers.
 */

// Content pages without a page model yet; their Markdown comes later.
const NO_MARKDOWN = new Set(["/", "/docs/api"]);
// Docs are Markdown files served as written, front matter included.
const DOCS = /^\/docs(\/[a-z0-9-]+)?$/;
const MAX_MARKDOWN_BYTES = 20 * 1024;
// More at once and next start drops connections while it renders uncached pages.
const CONCURRENCY = 4;

interface Facts {
  date: string | null;
  status: "open" | "closed" | null;
  reason: string | null;
  next: string | null;
  release: string | null;
}

/** The facts an AnswerLead states, whichever format it came from. */
function leadFacts(lead: string): Facts {
  return {
    date: /^(?:Yes|No): (\w+day \d+ \w+ \d{4})/.exec(lead)?.[1] ?? null,
    status: lead.startsWith("Yes:") ? "open" : lead.startsWith("No:") ? "closed" : null,
    reason: /closed for (.+?)(?:, according to|;)/.exec(lead)?.[1] ?? null,
    next: /(?:reopens on|business day is) (\w+day \d+ \w+ \d{4})/.exec(lead)?.[1] ?? null,
    release: /release (\d{4}\.\d{2}\.\d{2}\.\d+)/.exec(lead)?.[1] ?? null,
  };
}

function htmlLead(html: string): string {
  const match = /<p data-answer-lead=""[^>]*>(.*?)<\/p>/s.exec(html);
  return decode((match?.[1] ?? "").replace(/<!-- -->/g, ""));
}

function markdownLead(markdown: string): string {
  return (markdown.split("\n\n")[1] ?? "").replace(/\\([\\`*_[\]<>|])/g, "$1");
}

function decode(text: string): string {
  return text
    .replace(/&#x27;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function check(request: APIRequestContext, path: string): Promise<void> {
  const html = await request.get(path);
  expect(html.status(), path).toBe(200);
  expect(html.headers()["vary"] ?? "", `${path} Vary`).toMatch(/\bAccept\b/);
  const page = await html.text();
  expect(page, `${path} alternate`).toContain(
    `<link rel="alternate" type="text/markdown" href="http://localhost:3000${path === "/" ? "" : path}.md"/>`,
  );

  const md = await request.get(`${path}.md`);
  expect(md.status(), `${path}.md`).toBe(200);
  const headers = md.headers();
  const body = await md.body();
  expect(headers["content-type"], `${path}.md type`).toBe("text/markdown; charset=utf-8");
  expect(headers["content-length"], `${path}.md length`).toBe(String(body.byteLength));
  expect(headers["transfer-encoding"], `${path}.md chunked`).toBeUndefined();
  expect(headers["vary"] ?? "", `${path}.md Vary`).toMatch(/\bAccept\b/);
  expect(headers["link"], `${path}.md canonical`).toBe(
    `<http://localhost:3000${path}>; rel="canonical"`,
  );
  if (!/^\/calendars\/[^/]+\/\d{4}$/.test(path)) {
    expect(body.byteLength, `${path}.md size`).toBeLessThan(MAX_MARKDOWN_BYTES);
  }

  const markdown = body.toString("utf8");
  expect(markdown, `${path}.md format`).not.toMatch(/!\[|<img|Sign in/);
  if (DOCS.test(path)) {
    // The same file renders the page: its title is the page's h1.
    expect(markdown, `${path}.md front matter`).toMatch(/^---\ntitle: /);
    const title = /^# (.+)$/m.exec(markdown.replace(/^---\n[\s\S]*?\n---\n/, ""))?.[1];
    expect(page, `${path} h1`).toMatch(
      new RegExp(`<h1[^>]*>(<span>)?${escapeRegExp(title ?? "")}`),
    );
    return;
  }
  const fromHtml = htmlLead(page);
  expect(fromHtml, `${path} lead`).not.toBe("");
  expect(markdownLead(markdown), `${path} lead`).toBe(fromHtml);
  if (/^\/calendars\/[^/]+\/\d{4}-\d{2}-\d{2}$/.test(path)) {
    const facts = leadFacts(fromHtml);
    expect(leadFacts(markdownLead(markdown)), path).toEqual(facts);
    expect(facts.date, `${path} date`).not.toBeNull();
    expect(facts.status, `${path} status`).not.toBeNull();
    expect(facts.release, `${path} release`).not.toBeNull();
    // The band row for this date agrees with the lead.
    const row = markdown.split("\n").find((line) => line.endsWith("(this date) |")) ?? "";
    expect(row.includes(" open "), `${path} band`).toBe(facts.status === "open");
    if (facts.reason !== null) {
      expect(markdown, `${path} reason`).toContain(facts.reason);
    }
  }
}

test("every sitemap page has Markdown with the same facts", async ({ request }) => {
  test.setTimeout(30 * 60_000);
  const locations = (xml: string) =>
    [...xml.matchAll(/<loc>(.*?)<\/loc>/g)].map((match) => new URL(match[1]!).pathname);
  // /sitemap.xml is an index of the pages sitemap and one sitemap per calendar.
  const index = await (await request.get("/sitemap.xml")).text();
  const sitemaps = await Promise.all(
    locations(index).map(async (path) => (await request.get(path)).text()),
  );
  const paths = sitemaps.flatMap(locations).filter((path) => !NO_MARKDOWN.has(path));
  expect(paths.length).toBeGreaterThan(100);
  const queue = [...paths];
  await Promise.all(
    Array.from({ length: CONCURRENCY }, async () => {
      for (let path = queue.shift(); path !== undefined; path = queue.shift()) {
        await check(request, path);
      }
    }),
  );
});

test("?format=md is the same Markdown, and the wrong pair order redirects", async ({ request }) => {
  const path = "/calendars/GB-CHAPS/2026-12-28";
  const viaSuffix = await (await request.get(`${path}.md`)).text();
  const viaQuery = await request.get(`${path}?format=md`);
  expect(viaQuery.headers()["content-type"]).toBe("text/markdown; charset=utf-8");
  expect(await viaQuery.text()).toBe(viaSuffix);

  const redirect = await request.get("/compare/GB-CHAPS-vs-EU-T2/2027.md", { maxRedirects: 0 });
  expect(redirect.status()).toBe(308);
  expect(redirect.headers()["location"]).toBe("/compare/EU-T2-vs-GB-CHAPS/2027.md");
  expect((await request.get("/calendars/XX-NONE/2026-12-28.md")).status()).toBe(404);
});
