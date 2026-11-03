import { expect, test, type Page } from "@playwright/test";

import { expectAccessible, watchConsole } from "./support";

/** The visible lead: after a client navigation Next keeps the previous page mounted, hidden. */
const lead = (page: Page) => page.locator("[data-answer-lead]").filter({ visible: true });

test.describe("A1 public answer page", () => {
  test("answers whether a date is a business day, with sources and neighbours", async ({
    page,
  }) => {
    const problems = watchConsole(page);
    const response = await page.goto("/calendars/GB-CHAPS/2026-12-28");
    expect(response?.status()).toBe(200);

    await expect(
      page.getByRole("heading", {
        level: 1,
        name: "Is Monday 28 December 2026 a CHAPS business day?",
      }),
    ).toBeVisible();
    await expect(lead(page)).toContainText(
      "No: Monday 28 December 2026 is not a CHAPS business day, because CHAPS is closed for Boxing Day (substitute day)",
    );
    await expect(lead(page)).toContainText(
      "CHAPS reopens on Tuesday 29 December 2026 at 06:00 Europe/London",
    );
    await expect(page.getByRole("link", { name: "GOV.UK · UK bank holidays" })).toHaveAttribute(
      "href",
      "https://www.gov.uk/bank-holidays",
    );
    const next = page.getByRole("link", { name: "CHAPS reopens Tue 29 Dec 2026" }).first();
    await expect(next).toHaveAttribute("href", "/calendars/GB-CHAPS/2026-12-29");

    // The Zeitband and other calendars that day, as tables.
    await expect(
      page.getByRole("link", { name: "CHAPS closed Fri 25 Dec 2026: Christmas Day" }).first(),
    ).toBeAttached();
    const others = page.getByRole("table", { name: /on 7 other calendars/ });
    await expect(others.getByRole("row", { name: /Fedwire Funds/ })).toContainText("open");
    await expect(page.locator("details")).toHaveCount(3);
    await expect(page.getByText("Zeitwide (2026). GB-CHAPS on 2026-12-28.")).toBeVisible();

    await next.click();
    await expect(lead(page)).toContainText("Yes: Tuesday 29 December 2026 is a CHAPS business day");
    expect(problems).toEqual([]);
  });

  test("is search-ready: canonical, JSON-LD with the FAQ, sitemap and robots", async ({
    page,
    request,
  }) => {
    await page.goto("/calendars/GB-CHAPS/2026-12-28");
    await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
      "href",
      "http://localhost:3000/calendars/GB-CHAPS/2026-12-28",
    );
    const jsonLd = JSON.parse(
      (await page.locator('script[type="application/ld+json"]').textContent()) ?? "{}",
    ) as { "@graph": { "@type": string; name?: string; mainEntity?: unknown }[] };
    const nodes = jsonLd["@graph"];
    expect(nodes.map((node) => node["@type"])).toEqual([
      "Organization",
      "WebSite",
      "WebPage",
      "Question",
      "FAQPage",
      "BreadcrumbList",
    ]);
    expect(nodes.find((node) => node["@type"] === "Question")?.name).toContain("28 December 2026");
    expect(nodes.find((node) => node["@type"] === "FAQPage")?.mainEntity).toHaveLength(3);
    const csv = await request.get("/downloads/GB-CHAPS.csv");
    expect(csv.headers()["content-type"]).toBe("text/csv; charset=utf-8");
    expect(await csv.text()).toMatch(/^date,is_open,reason,basis,source_urls\r\n/);

    // The pipeline's files for the release: a sitemap index, per-calendar sitemaps, robots.txt.
    const index = await request.get("/sitemap.xml");
    expect(index.headers()["cache-control"]).toBe("public, max-age=3600");
    expect(await index.text()).toContain(
      "<loc>http://localhost:3000/sitemaps/GB-CHAPS.xml</loc><lastmod>2025-12-01</lastmod>",
    );
    const chaps = await (await request.get("/sitemaps/GB-CHAPS.xml")).text();
    expect(chaps).toContain("<loc>http://localhost:3000/calendars/GB-CHAPS/2026-12-28</loc>");
    expect(chaps).toContain("<loc>http://localhost:3000/calendars/GB-CHAPS/2027</loc>");
    const pages = await (await request.get("/sitemaps/pages.xml")).text();
    expect(pages).toContain("<loc>http://localhost:3000/compare/EU-T2-vs-GB-CHAPS/2027</loc>");
    const robots = await (await request.get("/robots.txt")).text();
    expect(robots).toContain("Disallow: /app/");
    expect(robots).toContain("User-agent: Claude-User");
    expect(robots).toContain("Sitemap: http://localhost:3000/sitemap.xml");
    expect(await (await request.get("/llms.txt")).text()).toMatch(/^# Zeitwide\n\n> /);
  });

  test("lists a year of closures and compares two calendars", async ({ page }) => {
    await page.goto("/calendars/EU-T2/2027");
    await expect(
      page.getByRole("heading", { level: 1, name: "T2 closures in 2027" }),
    ).toBeVisible();
    await expect(lead(page)).toContainText(/T2 has \d+ closing days in 2027/);
    await page.getByRole("link", { name: /^T2 and CHAPS share/ }).click();
    await expect(page).toHaveURL("/compare/EU-T2-vs-GB-CHAPS/2027");
    await expect(lead(page)).toContainText(/^In 2027 T2 closes on/);

    // One URL per pair: the other order redirects permanently.
    await page.goto("/compare/GB-CHAPS-vs-EU-T2/2027");
    await expect(page).toHaveURL("/compare/EU-T2-vs-GB-CHAPS/2027");
  });

  test("is served with CDN cache headers and strict security headers", async ({ request }) => {
    const response = await request.get("/calendars/US-FEDWIRE/2026-11-26");
    const headers = response.headers();
    expect(headers["cache-control"]).toMatch(/s-maxage=\d+/);
    expect(headers["content-security-policy"]).toContain("frame-ancestors 'none'");
    expect(headers["content-security-policy"]).toContain("object-src 'none'");
    expect(headers["strict-transport-security"]).toContain("max-age=63072000");
    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
  });

  test("generates a date outside the prebuilt window on demand, and 404s unknown ones", async ({
    page,
  }) => {
    const response = await page.goto("/calendars/GB-CHAPS/2022-09-19");
    expect(response?.status()).toBe(200);
    await expect(page.getByText("State Funeral of Queen Elizabeth II").first()).toBeVisible();
    expect((await page.goto("/calendars/GB-CHAPS/2099-01-01"))?.status()).toBe(404);
    expect((await page.goto("/calendars/XX-NONE/2026-12-28"))?.status()).toBe(404);
  });

  test("checks a date on the home page and opens its answer", async ({ page }) => {
    await page.goto("/");
    await expect(
      page.getByRole("heading", { name: "Business days you can prove.", level: 1 }),
    ).toBeVisible();
    await page.getByRole("button", { name: "T2", exact: true }).click();
    await page.getByRole("button", { name: "Fri 25 Dec" }).click();
    await expect(page.getByText("No, closed.")).toBeVisible();
    await expect(page.getByText("Christmas Day.", { exact: false }).first()).toBeVisible();
    await page.getByRole("link", { name: "See the full answer and its evidence" }).click();
    await expect(page).toHaveURL("/calendars/EU-T2/2026-12-25");
  });

  for (const path of [
    "/",
    "/calendars",
    "/calendars/GB-CHAPS",
    "/calendars/GB-CHAPS/2026-12-28",
    "/calendars/GB-CHAPS/2027",
    "/compare/EU-T2-vs-GB-CHAPS/2027",
    "/sources",
    "/pricing",
    "/docs",
    "/docs/api",
    "/signin",
    "/signup",
    "/does-not-exist",
  ]) {
    test(`${path} has no accessibility violations`, async ({ page }) => {
      await page.goto(path);
      await expectAccessible(page);
    });
  }
});

test("C4 API reference is generated from the contract", async ({ page }) => {
  await page.goto("/docs/api");
  await expect(page.getByRole("heading", { name: "API reference", level: 1 })).toBeVisible();
  await expect(page.getByText("/v1/calendars/{id}/days/{date}", { exact: true })).toBeVisible();
  await expect(page.getByText("/v1/admin/candidates/{id}/decision", { exact: true })).toBeVisible();
  await expect(page.locator("#schema-RangeDay")).toContainText("next_business_day");
});
