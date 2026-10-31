import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export const KEYCLOAK = "http://localhost:8180";
export const MOCK_API = "http://127.0.0.1:8090";

export const USERS = {
  member: { username: "e2e-member", firstName: "Grace" },
  reviewer: { username: "e2e-reviewer", firstName: "Priya" },
} as const;
export type Role = keyof typeof USERS;

export const STATE = {
  dir: "e2e/.auth",
  member: "e2e/.auth/member.json",
  reviewer: "e2e/.auth/reviewer.json",
} as const;

/** The real authorization-code flow: the app, then Keycloak's login form, then back. */
export async function signInThroughKeycloak(page: Page, username: string, password: string) {
  await page.goto("/app/explorer");
  await expect(page).toHaveURL(/\/signin/);
  await page.getByRole("button", { name: "GitHub or company single sign-on" }).click();
  await page.waitForURL(/localhost:8180\/realms\/zeitwide/);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await page.waitForURL(/\/app\/explorer/);
}

/** No WCAG 2.x A or AA violations on the page as it stands. */
export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`),
  ).toEqual([]);
}

/** Collects Content-Security-Policy violations and page errors while a test runs. */
export function watchConsole(page: Page): string[] {
  const problems: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && /Content Security Policy|Refused to/i.test(message.text())) {
      problems.push(message.text());
    }
  });
  page.on("pageerror", (error) => problems.push(error.message));
  return problems;
}
