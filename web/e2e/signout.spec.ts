import { expect, test } from "@playwright/test";

import { signInThroughKeycloak, USERS } from "./support";

// Signs in afresh (global setup made the password), so the test owns its Keycloak session.
test("sign out ends the Keycloak session too, so the next sign-in asks again", async ({ page }) => {
  await signInThroughKeycloak(page, USERS.member.username, process.env.E2E_PASSWORD_MEMBER ?? "");
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL("http://localhost:3000/");
  expect((await page.context().cookies()).map((cookie) => cookie.name)).not.toContainEqual(
    expect.stringMatching(/^authjs\.session-token/),
  );

  await page.goto("/app/explorer");
  await expect(page).toHaveURL(/\/signin/);
  await page.getByRole("button", { name: "GitHub or company single sign-on" }).click();
  // Keycloak shows its login form instead of signing the previous user straight back in.
  await expect(page.locator("#username")).toBeVisible();
});
