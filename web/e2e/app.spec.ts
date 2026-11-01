import { expect, test } from "@playwright/test";

import { expectAccessible, MOCK_API, STATE, watchConsole } from "./support";

test.use({ storageState: STATE.member });

test.beforeEach(async ({ request }) => {
  await request.post(`${MOCK_API}/__reset`);
});

test("signed-out visitors are sent to sign in", async ({ browser }) => {
  const page = await (
    await browser.newContext({ storageState: { cookies: [], origins: [] } })
  ).newPage();
  await page.goto("/app/calculator");
  await expect(page).toHaveURL(/\/signin\?callbackUrl=%2Fapp%2Fcalculator/);
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with Microsoft" })).toBeVisible();
});

test("sign-up offers the same providers and links back to sign in", async ({ browser }) => {
  const page = await (
    await browser.newContext({ storageState: { cookies: [], origins: [] } })
  ).newPage();
  await page.goto("/signup?callbackUrl=%2Fapp%2Fchanges");
  await expect(page.getByRole("heading", { name: "Create your free account" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with GitHub" })).toBeVisible();
  await page.getByRole("link", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/signin\?callbackUrl=%2Fapp%2Fchanges/);
});

for (const [provider, host, id] of [
  ["Google", "accounts.google.com", "e2e-google-id"],
  ["Microsoft", "login.microsoftonline.com", "e2e-microsoft-id"],
  ["GitHub", "github.com", "e2e-github-id"],
] as const) {
  test(`Continue with ${provider} sends the visitor to ${provider} with the right callback`, async ({
    browser,
  }) => {
    const page = await (
      await browser.newContext({ storageState: { cookies: [], origins: [] } })
    ).newPage();
    // Stand in for the provider: all that matters is where the app sends the browser.
    const sent = page.waitForRequest((request) => new URL(request.url()).hostname === host);
    await page.route(`https://${host}/**`, (route) =>
      route.fulfill({ status: 200, contentType: "text/html", body: "<h1>provider</h1>" }),
    );
    await page.goto("/signin");
    await page.getByRole("button", { name: `Continue with ${provider}` }).click();
    const url = new URL((await sent).url());
    expect(url.searchParams.get("client_id")).toBe(id);
    expect(url.searchParams.get("redirect_uri")).toBe(
      `http://localhost:3000/api/auth/callback/${provider.toLowerCase()}`,
    );
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("state")).not.toBeNull();
  });
}

test("a visitor who is already signed in skips the sign-in page", async ({ page }) => {
  await page.goto("/signin?callbackUrl=%2Fapp%2Fchanges");
  await expect(page).toHaveURL(/\/app\/changes/);
  await page.goto("/signup");
  await expect(page).toHaveURL(/\/app\/developers/);
});

test("sign-in never redirects off site", async ({ page }) => {
  await page.goto("/signin?callbackUrl=https%3A%2F%2Fevil.example%2F");
  await expect(page).toHaveURL(/\/app$/);
});

test("P2 today shows every rail, what is coming and a quick check", async ({ page }) => {
  await page.goto("/app");
  await expect(
    page.getByRole("heading", { name: /settles? today|settle today/, level: 1 }),
  ).toBeVisible();
  const rails = page.getByRole("region", { name: "Right now" });
  await expect(rails.getByRole("link", { name: "CHAPS" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Coming up" })).toBeVisible();
  await page.getByLabel("Rail", { exact: true }).selectOption("EU-T2");
  await page.getByLabel("Date", { exact: true }).fill("2026-12-25");
  await expect(page.getByText("Closed. Christmas Day")).toBeVisible();
  await expectAccessible(page);
});

test("A2 timetable shows each calendar's days and explains a selected day", async ({ page }) => {
  const problems = watchConsole(page);
  await page.goto("/app/explorer");
  await expect(page.getByRole("heading", { name: "Timetable", level: 1 })).toBeVisible();
  await page.getByLabel("From", { exact: true }).fill("2026-12-21");
  await page.getByLabel("To", { exact: true }).fill("2027-01-04");
  await page.getByRole("button", { name: "Show these dates" }).click();
  const cell = page.getByRole("button", {
    name: "CHAPS, Monday 28 December 2026: Boxing Day (substitute day)",
  });
  await cell.click();
  await expect(cell).toHaveAttribute("aria-pressed", "true");
  const panel = page.getByRole("heading", { name: "Monday 28 December 2026" });
  await expect(panel).toBeVisible();
  await expect(page.getByText("Not open on every chosen calendar")).toBeVisible();
  await expectAccessible(page);
  expect(problems).toEqual([]);
});

test("A3 evidence lists the sources and verifies the signed receipt", async ({ page }) => {
  const problems = watchConsole(page);
  await page.goto("/app/evidence/GB-CHAPS/2026-12-28");
  await expect(
    page.getByRole("heading", { name: /How we know: CHAPS on Monday 28 December 2026/ }),
  ).toBeVisible();
  await expect(page.getByText("A · GOV.UK · UK bank holidays")).toBeVisible();
  await expect(page.getByText("B · Bank of England · CHAPS and bank holidays")).toBeVisible();
  await page.getByRole("button", { name: "Verify receipt" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Signature valid" })).toBeVisible();
  await expectAccessible(page);
  expect(problems).toEqual([]);
});

test("A4 planner adds business days across rails and lists what it skipped", async ({ page }) => {
  // The form is a client component: until the page has hydrated, Calculate submits it natively
  // and empties it, so wait for the page to settle before typing.
  await page.goto("/app/calculator", { waitUntil: "networkidle" });
  await page.getByLabel("You instruct on").fill("2026-12-24");
  await page.getByLabel("Business days to add").fill("2");
  await page.getByRole("button", { name: "Calculate" }).click();
  await expect(page.getByRole("heading", { name: "Wednesday 30 December 2026" })).toBeVisible();
  await expect(page.getByText("CHAPS: Boxing Day (substitute day)")).toBeVisible();
  await expect(page.getByText("Fedwire Funds: Christmas Day")).toBeVisible();
  await expectAccessible(page);
});

test("A5 audit replays an answer as of a past moment", async ({ page }) => {
  await page.goto("/app/time-travel?calendar=GB-CHAPS&date=2022-09-19");
  await page.getByLabel("As of (UTC)").fill("2022-09-09T12:00");
  await page.getByRole("button", { name: "Replay" }).click();
  await expect(page.getByText("The answer has changed since then.")).toBeVisible();
  await expect(page.getByText("Open", { exact: true })).toBeVisible();
  await expect(page.getByText("Closed", { exact: true })).toBeVisible();
  await expect(page.getByText(/closure added with 9 days' notice/)).toBeVisible();
  await expectAccessible(page);
});

test("P7 audit checks a pasted receipt's signature", async ({ page, request }) => {
  const { receipt } = (await (await request.get(`${MOCK_API}/v1/calendars`)).json()) as {
    receipt: unknown;
  };
  await page.goto("/app/time-travel");
  await page.getByLabel("The full receipt, as JSON").fill("not json");
  await page.getByRole("button", { name: "Check", exact: true }).click();
  await expect(page.getByText("That isn't a JSON receipt")).toBeVisible();
  await page.getByLabel("The full receipt, as JSON").fill(JSON.stringify(receipt));
  await page.getByRole("button", { name: "Check", exact: true }).click();
  await expect(page.getByText("Signature valid")).toBeVisible();
  await expectAccessible(page);
});

test("A6 changes lists newest first and manages alert rules", async ({ page }) => {
  await page.goto("/app/changes");
  const titles = page.getByRole("heading", { level: 3 });
  await expect(titles.first()).toHaveText("T2 on Friday 26 March 2027");

  const form = page.getByRole("form", { name: "New alert rule" });
  await form.getByLabel("Name").fill("Short-notice GBP");
  await form.getByRole("checkbox", { name: /CHAPS/ }).check();
  await form.getByLabel(/days' notice or less/).fill("14");
  await form.getByRole("button", { name: "Add rule" }).click();
  await expect(page.getByText("Short-notice GBP")).toBeVisible();
  await expect(page.getByText("GB-CHAPS · 14 days' notice or less")).toBeVisible();

  await page.getByLabel("Only calendars in my alert rules").check();
  await expect(titles.filter({ hasText: "T2" })).toHaveCount(0);
  await expect(titles.first()).toHaveText("CHAPS on Monday 28 December 2026");

  await page.getByRole("button", { name: "Edit Short-notice GBP" }).click();
  await page.getByRole("form", { name: "Edit Short-notice GBP" }).getByLabel("Name").fill("GBP");
  await page.getByRole("button", { name: "Save rule" }).click();
  await expect(page.getByText("GBP", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Delete GBP" }).click();
  await expect(page.getByText("GB-CHAPS · 14 days' notice or less")).toHaveCount(0);
  await expectAccessible(page);
});

test("C1 creates and revokes API keys and webhooks, showing secrets once", async ({ page }) => {
  await page.goto("/app/developers");
  await page.getByLabel("Name this key").fill("payments-service");
  await page.getByRole("button", { name: "Create key" }).click();
  const secret = page.getByRole("status").filter({ hasText: "Your key for payments-service" });
  await expect(secret).toContainText(/zw_live_[A-Za-z0-9]{8}_/);
  await secret.getByRole("button", { name: "I've saved it" }).click();
  await expect(page.getByText(/zw_live_[A-Za-z0-9]{8}_[A-Za-z0-9]{10}/)).toHaveCount(0);
  page.once("dialog", (dialog) => void dialog.accept());
  await page.getByRole("button", { name: "Revoke payments-service" }).click();
  await expect(page.getByText(/^Revoked /)).toBeVisible();

  await page.getByLabel("Endpoint URL").fill("https://ops.example.com/hooks/zeitwide");
  await page.getByRole("button", { name: "Add webhook" }).click();
  const hookSecret = page.getByRole("status").filter({ hasText: "Signing secret" });
  await expect(hookSecret).toContainText("whsec_");
  await hookSecret.getByRole("button", { name: "I've saved it" }).click();
  await page
    .getByRole("button", { name: "Send a test event to https://ops.example.com/hooks/zeitwide" })
    .click();
  await expect(page.getByText("Test delivered (HTTP 200)")).toBeVisible();
  await page.getByRole("button", { name: "Delete https://ops.example.com/hooks/zeitwide" }).click();
  await expect(page.getByText("https://ops.example.com/hooks/zeitwide")).toHaveCount(0);
  await expectAccessible(page);
});

test("C2 MCP setup shows hosted and local configurations", async ({ page }) => {
  const problems = watchConsole(page);
  await page.goto("/app/developers/mcp");
  await expect(page.getByText('"url": "http://127.0.0.1:8090/mcp"')).toBeVisible();
  await expect(page.getByText('"zeitwide-mcp"')).toBeVisible();
  await expect(page.getByRole("cell", { name: "add_business_days" })).toBeVisible();
  await expectAccessible(page);
  expect(problems).toEqual([]);
});

test("members can't open the admin screens", async ({ page }) => {
  await page.goto("/admin/review");
  await expect(page.getByRole("heading", { name: "Reviewers only" })).toBeVisible();
});

test("signed-in pages carry a nonce CSP and never leak tokens to the browser", async ({ page }) => {
  const response = await page.goto("/app/calculator");
  expect(response?.headers()["content-security-policy"]).toMatch(
    /script-src 'self' 'nonce-[^']+' 'strict-dynamic'/,
  );
  const session = await (await page.request.get("/api/auth/session")).json();
  expect(JSON.stringify(session)).not.toMatch(/accessToken|refreshToken|eyJ/);
  expect(session.user.name).toBe("Grace Tester");
  // Cross-site writes through the API proxy are refused.
  const forged = await page.request.post("/api/zw/v1/account/api-keys", {
    headers: { origin: "https://evil.example", "content-type": "application/json" },
    data: { name: "x" },
  });
  expect(forged.status()).toBe(403);
});
