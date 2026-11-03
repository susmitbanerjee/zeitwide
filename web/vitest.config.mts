import { fileURLToPath } from "node:url";

import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "server-only": fileURLToPath(new URL("./src/test/server-only-stub.ts", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.{ts,tsx}", "eslint-rules/**/*.test.mjs"],
    coverage: {
      provider: "v8",
      // Logic and route handlers are unit-tested here. Pages and components (*.tsx) render in
      // Playwright (e2e/), with axe on every screen; cached reads, client hooks and the Auth.js
      // wiring need the Next.js runtime or a browser, so the e2e flows cover them too.
      include: ["src/**/*.ts"],
      exclude: [
        "src/**/*.test.ts",
        "src/**/*.test-data.ts",
        "src/test/**",
        "src/**/*.d.ts",
        "src/auth.ts",
        "src/proxy.ts",
        "src/lib/api-server.ts",
        "src/lib/public-data.ts",
        "src/lib/api-browser.ts",
        "src/lib/queries.ts",
        // Types only, generated from api/openapi.yaml.
        "src/api/schema.ts",
      ],
      thresholds: { lines: 80, functions: 80, branches: 80, statements: 80 },
    },
  },
});
