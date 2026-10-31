// Builds the app for the end-to-end tests: starts the fake API, runs `next build` against it
// (answer pages prerender from the API), then stops the fake API.
import { spawn } from "node:child_process";

const api = spawn(process.execPath, ["e2e/mock-api/server.mjs"], {
  env: { ...process.env, MOCK_API_PORT: "8090" },
  stdio: "inherit",
});
await new Promise((resolve) => setTimeout(resolve, 1000));
const build = spawn("npx", ["next", "build"], {
  env: {
    ...process.env,
    ZEITWIDE_API_URL: "http://127.0.0.1:8090",
    SITE_URL: process.env.SITE_URL ?? "http://localhost:3000",
  },
  stdio: "inherit",
  shell: true,
});
const code = await new Promise((resolve) => build.on("exit", resolve));
api.kill();
process.exit(code ?? 1);
