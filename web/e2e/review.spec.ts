import { expect, test } from "@playwright/test";

import { expectAccessible, MOCK_API, STATE, watchConsole } from "./support";

test.use({ storageState: STATE.reviewer });

test.beforeEach(async ({ request }) => {
  await request.post(`${MOCK_API}/__reset`);
});

test("a reviewer approves a candidate after comparing its snapshots", async ({ page }) => {
  const problems = watchConsole(page);
  await page.goto("/admin/review");
  const rows = page.getByRole("row");
  // Urgent first: 10 Nov has under 14 days' notice.
  await expect(rows.nth(1)).toContainText("US-FEDWIRE · Tue 10 Nov 2026");
  await expect(rows.nth(1)).toContainText("Urgent");
  await expect(rows.nth(1)).toContainText("Single source");
  await expectAccessible(page);

  await page.getByRole("link", { name: "GB-CHAPS · Mon 10 May 2027" }).click();
  await expect(page.getByRole("heading", { name: "Snapshots, side by side" })).toBeVisible();
  const snapshots = page.getByRole("list").filter({ hasText: "GOV.UK" });
  await expect(snapshots.getByRole("listitem")).toHaveCount(2);
  await expect(snapshots).toContainText("Bank of England");
  await expectAccessible(page);

  const approve = page.getByRole("button", { name: "Approve" });
  await expect(approve).toBeDisabled();
  await page.getByLabel(/Reason/).fill("GOV.UK and the Bank of England agree.");
  await approve.click();
  await expect(page.getByRole("status")).toContainText("approved");
  await expect(page.getByRole("status")).toContainText("GOV.UK and the Bank of England agree.");

  await page.getByRole("link", { name: "Back to the queue" }).click();
  await expect(page.getByRole("link", { name: "GB-CHAPS · Mon 10 May 2027" })).toHaveCount(0);
  await page.getByLabel("Show").selectOption("approved");
  await expect(page.getByRole("link", { name: "GB-CHAPS · Mon 10 May 2027" })).toBeVisible();
  expect(problems).toEqual([]);
});

test("a reviewer rejects a single-source candidate", async ({ page }) => {
  await page.goto("/admin/review/41");
  await page.getByLabel(/Reason/).fill("Only one source; the Board has not confirmed.");
  await page.getByRole("button", { name: "Reject" }).click();
  await expect(page.getByRole("status")).toContainText("rejected");
});

test("source health flags failing sources, and releases list newest first", async ({ page }) => {
  await page.goto("/admin/sources");
  await expect(page.getByText("1 source needs attention.")).toBeVisible();
  await expect(page.getByRole("row", { name: /Bank of England/ })).toContainText("Failing");
  await expectAccessible(page);

  await page.goto("/admin/releases");
  await expect(page.getByRole("row").nth(1)).toContainText("2026.10.31.1");
  await expectAccessible(page);
});
