import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end flows against a production build and the fake API (e2e/mock-api), signing in
 * with the app's test-only sign-in. Run `npm run e2e:build` first, then `npm run e2e`.
 */
const PORT = 3000;
const MOCK_PORT = 8090;
export const BASE_URL = `http://localhost:${PORT}`;
export const MOCK_API_URL = `http://127.0.0.1:${MOCK_PORT}`;

export default defineConfig({
  testDir: "e2e",
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI === undefined ? 0 : 1,
  reporter: [["list"], ["html", { open: "never" }]],
  timeout: 60_000,
  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    ...devices["Desktop Chrome"],
  },
  webServer: [
    {
      command: "node e2e/mock-api/server.mjs",
      url: `${MOCK_API_URL}/v1/calendars`,
      env: { MOCK_API_PORT: String(MOCK_PORT) },
      reuseExistingServer: true,
    },
    {
      command: `npx next start -p ${PORT}`,
      url: `${BASE_URL}/api/health`,
      reuseExistingServer: true,
      timeout: 120_000,
      env: {
        ZEITWIDE_API_URL: MOCK_API_URL,
        SITE_URL: BASE_URL,
        APP_URL: BASE_URL,
        AUTH_URL: BASE_URL,
        AUTH_SECRET: "e2e-only-auth-secret-0123456789abcdef0123456789",
        ZEITWIDE_API_TOKEN_SECRET: "e2e-only-api-token-secret-0123456789abcdef",
        // Fake OAuth apps: enough for the sign-in buttons to appear and redirect.
        AUTH_GOOGLE_ID: "e2e-google-id",
        AUTH_GOOGLE_SECRET: "e2e-google-secret",
        AUTH_MICROSOFT_ID: "e2e-microsoft-id",
        AUTH_MICROSOFT_SECRET: "e2e-microsoft-secret",
        // A fake Microsoft the sign-in test starts itself (honoured on localhost only).
        AUTH_MICROSOFT_TEST_BASE: "http://127.0.0.1:8091",
        AUTH_GITHUB_ID: "e2e-github-id",
        AUTH_GITHUB_SECRET: "e2e-github-secret",
        ZEITWIDE_TEST_LOGIN: "1",
        ZEITWIDE_WEBHOOK_SECRET: "whsec_e2e_only_secret",
      },
    },
  ],
});
