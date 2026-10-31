import { expect, test } from "@playwright/test";

import { expectAccessible, watchConsole } from "./support";

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
    await expect(page.getByText("No.", { exact: true })).toBeVisible();
    await expect(page.getByText("CHAPS is closed: Boxing Day (substitute day).")).toBeVisible();
    await expect(page.getByRole("link", { name: "GOV.UK · UK bank holidays" })).toHaveAttribute(
      "href",
      "https://www.gov.uk/bank-holidays",
    );
    const next = page.getByRole("link", { name: "Tue 29 Dec 2026" }).first();
    await expect(next).toHaveAttribute("href", "/calendars/GB-CHAPS/2026-12-29");
    await expect(page.getByRole("link", { name: "Thu 24 Dec 2026" }).first()).toBeVisible();

    // Other calendars that day, and the strip around it.
    const others = page.getByRole("table");
    await expect(others.getByRole("row", { name: /Fedwire Funds/ })).toContainText("Open");
    await expect(page.getByRole("link", { name: /Friday 25 December 2026: closed/ })).toBeVisible();

    await next.click();
    await expect(page.getByText("Yes.", { exact: true })).toBeVisible();
    expect(problems).toEqual([]);
  });

  test("is search-ready: canonical, JSON-LD, sitemap and people also check", async ({
    page,
    request,
  }) => {
    await page.goto("/calendars/GB-CHAPS/2026-12-28");
    await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
      "href",
      "http://localhost:3000/calendars/GB-CHAPS/2026-12-28",
    );
    const jsonLd = JSON.parse(
      (await page.locator('script[type="application/ld+json"]').textContent()) ?? "[]",
    ) as { "@type": string; mainEntity?: { name: string }[] }[];
    expect(jsonLd.map((item) => item["@type"])).toEqual(["FAQPage", "BreadcrumbList"]);
    expect(jsonLd[0]?.mainEntity?.[0]?.name).toContain("28 December 2026");

    const also = page.getByRole("complementary", { name: "People also check" });
    await expect(also.getByRole("link")).toHaveCount(6);
    await expect(also.getByRole("link", { name: /Next CHAPS closure/ })).toHaveAttribute(
      "href",
      "/calendars/GB-CHAPS/2027-01-01",
    );

    const sitemap = await (await request.get("/sitemap.xml")).text();
    expect(sitemap).toContain("<loc>http://localhost:3000/calendars/GB-CHAPS/2026-12-28</loc>");
    const robots = await (await request.get("/robots.txt")).text();
    expect(robots).toContain("Disallow: /app");
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

  test("finds a date from the home page", async ({ page }) => {
    await page.goto("/");
    await page.getByLabel("Calendar", { exact: true }).selectOption("EU-T2");
    await page.getByLabel("Date").fill("2026-04-03");
    await page.getByRole("button", { name: "Check the date" }).click();
    await expect(page).toHaveURL("/calendars/EU-T2/2026-04-03");
    await expect(page.getByText("T2 is closed: Good Friday.")).toBeVisible();
  });

  for (const path of [
    "/",
    "/calendars",
    "/calendars/GB-CHAPS",
    "/calendars/GB-CHAPS/2026-12-28",
    "/docs",
    "/docs/api",
    "/signin",
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
