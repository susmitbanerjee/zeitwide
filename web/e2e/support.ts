import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export const MOCK_API = "http://127.0.0.1:8090";

export const USERS = {
  member: { label: "member", firstName: "Grace" },
  reviewer: { label: "reviewer", firstName: "Priya" },
} as const;
export type Role = keyof typeof USERS;

export const STATE = {
  dir: "e2e/.auth",
  member: "e2e/.auth/member.json",
  reviewer: "e2e/.auth/reviewer.json",
} as const;

/**
 * Signs in with the app's test-only sign-in (ZEITWIDE_TEST_LOGIN, localhost only): the real
 * session cookie and the real signed calls to the API, without a Google account.
 */
export async function signInAs(page: Page, role: Role) {
  await page.goto("/signin?callbackUrl=%2Fapp%2Fexplorer");
  await page.getByRole("button", { name: `Test sign-in: ${USERS[role].label}` }).click();
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
