import { expect, test } from "@playwright/test";

import { signInAs } from "./support";

test("sign out ends the session, so the app asks who you are again", async ({ page }) => {
  await signInAs(page, "member");
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL("http://localhost:3000/");
  expect((await page.context().cookies()).map((cookie) => cookie.name)).not.toContainEqual(
    expect.stringMatching(/^authjs\.session-token/),
  );

  await page.goto("/app/explorer");
  await expect(page).toHaveURL(/\/signin/);
});
