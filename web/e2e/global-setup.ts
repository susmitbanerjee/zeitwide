import { mkdirSync } from "node:fs";

import { chromium, type FullConfig } from "@playwright/test";

import { MOCK_API, signInAs, STATE, USERS, type Role } from "./support";

/** Signs each test user in once, through the app's test sign-in, and keeps the session. */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL ?? "http://localhost:3000";
  mkdirSync(STATE.dir, { recursive: true });
  const browser = await chromium.launch();
  for (const role of Object.keys(USERS) as Role[]) {
    const page = await browser.newPage({ baseURL });
    await page.request.post(`${MOCK_API}/__reset`);
    await signInAs(page, role);
    await page.context().storageState({ path: STATE[role] });
    await page.close();
  }
  await browser.close();
}
