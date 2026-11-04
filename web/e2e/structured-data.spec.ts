import { createHash } from "node:crypto";

import Validator from "@adobe/structured-data-validator";
import WebAutoExtractor from "@marbec/web-auto-extractor";
import { expect, test, type APIRequestContext } from "@playwright/test";
import { JSDOM } from "jsdom";

import { checkStructuredData } from "../src/lib/structured-data-check";

/**
 * Every page's JSON-LD against the built HTML: the consistency check for every sitemap URL, and
 * the schema.org validator for one page of each template. Needs only HTTP, no browser.
 */

// schema.org 30.1, pinned by hash: a changed vocabulary is a deliberate upgrade, never a surprise.
const SCHEMA_ORG_URL =
  "https://raw.githubusercontent.com/schemaorg/schemaorg/main/data/releases/30.1/schemaorg-all-https.jsonld";
const SCHEMA_ORG_SHA256 = "07e7c663dbc12a0937581745e59a98ed12b8698f38e8e3b03650287d9df88ae5";
// More at once and next start drops connections while it renders uncached pages.
const CONCURRENCY = 4;

const TEMPLATES = [
  "/",
  "/calendars",
  "/calendars/GB-CHAPS",
  "/calendars/GB-CHAPS/2026",
  "/calendars/GB-CHAPS/2026-12-28",
  "/compare/EU-T2-vs-GB-CHAPS/2026",
  "/sources",
  "/pricing",
  "/docs",
  "/docs/rules",
  "/docs/api",
];

async function sitemapPaths(request: APIRequestContext): Promise<string[]> {
  const locations = (xml: string) =>
    [...xml.matchAll(/<loc>(.*?)<\/loc>/g)].map((match) => new URL(match[1]!).pathname);
  const index = await (await request.get("/sitemap.xml")).text();
  const sitemaps = await Promise.all(
    locations(index).map(async (path) => (await request.get(path)).text()),
  );
  return sitemaps.flatMap(locations);
}

async function html(request: APIRequestContext, path: string): Promise<string> {
  const response = await request.get(path);
  expect(response.status(), path).toBe(200);
  return response.text();
}

test("every sitemap page's JSON-LD states only what the page shows", async ({
  request,
  baseURL,
}) => {
  test.setTimeout(30 * 60_000);
  const paths = await sitemapPaths(request);
  expect(paths.length).toBeGreaterThan(100);
  for (const template of TEMPLATES) {
    expect(paths, `the sitemap lists ${template}`).toContain(template);
  }
  const queue = [...paths];
  await Promise.all(
    Array.from({ length: CONCURRENCY }, async () => {
      for (let path = queue.shift(); path !== undefined; path = queue.shift()) {
        const { document } = new JSDOM(await html(request, path)).window;
        // Soft, so one run names every page that disagrees.
        expect
          .soft(checkStructuredData(document, new URL(path, baseURL).toString()), path)
          .toEqual([]);
      }
    }),
  );
});

test("one page of each template passes the schema.org validator", async ({ request }) => {
  const vocabulary = await request.get(SCHEMA_ORG_URL);
  expect(vocabulary.status()).toBe(200);
  const bytes = await vocabulary.body();
  expect(createHash("sha256").update(bytes).digest("hex")).toBe(SCHEMA_ORG_SHA256);
  const validator = new Validator(JSON.parse(bytes.toString("utf8")));
  for (const path of TEMPLATES) {
    const extracted = new WebAutoExtractor({
      addLocation: true,
      embedSource: ["rdfa", "microdata"],
    }).parse(await html(request, path)) as { jsonld: Record<string, unknown[]> };
    // The validator passes a page with no JSON-LD, so first make sure it found the graph.
    expect(Object.keys(extracted.jsonld), path).toContain("WebPage");
    // Warnings too: an unknown property or type is a warning, and none is acceptable.
    const issues = await validator.validate(extracted);
    expect(
      issues.map((issue) => `${issue.severity}: ${issue.issueMessage}`),
      path,
    ).toEqual([]);
  }
});
