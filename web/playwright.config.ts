import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end flows against a production build, the fake API (e2e/mock-api) and the real local
 * Keycloak from docker compose (`make up`). Run `npm run build` first, with ZEITWIDE_API_URL
 * pointing at the fake API, then `npm run e2e`.
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
        AUTH_KEYCLOAK_ISSUER: "http://localhost:8180/realms/zeitwide",
        AUTH_KEYCLOAK_ID: "zeitwide-web",
        ZEITWIDE_WEBHOOK_SECRET: "whsec_e2e_only_secret",
      },
    },
  ],
});
