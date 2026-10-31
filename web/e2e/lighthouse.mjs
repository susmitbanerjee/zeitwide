// Lighthouse SEO and accessibility on an answer page; fails below 95.
//   node e2e/lighthouse.mjs [url]     (needs the production server from `npm run e2e`)
import { writeFileSync, mkdirSync } from "node:fs";

import { chromium } from "@playwright/test";
import lighthouse from "lighthouse";

const url = process.argv[2] ?? "http://localhost:3000/calendars/GB-CHAPS/2026-12-28";
const MINIMUM = 95;

const PORT = 9223;
// Playwright's own Chromium, opened for Lighthouse on a debugging port.
const browser = await chromium.launch({ args: [`--remote-debugging-port=${PORT}`] });
try {
  const result = await lighthouse(url, {
    port: PORT,
    output: "html",
    onlyCategories: ["seo", "accessibility"],
    logLevel: "error",
  });
  if (result === undefined) {
    throw new Error("Lighthouse returned no result");
  }
  mkdirSync(".lighthouse", { recursive: true });
  writeFileSync(".lighthouse/answer-page.html", result.report);
  let failed = false;
  for (const category of Object.values(result.lhr.categories)) {
    const score = Math.round((category.score ?? 0) * 100);
    console.warn(`${category.title}: ${score}`);
    if (score < MINIMUM) {
      failed = true;
      for (const ref of category.auditRefs) {
        const audit = result.lhr.audits[ref.id];
        if (ref.weight > 0 && audit !== undefined && audit.score !== null && audit.score < 1) {
          console.warn(`  failing: ${audit.id} (${audit.title})`);
        }
      }
    }
  }
  if (failed) {
    process.exitCode = 1;
  }
} finally {
  await browser.close();
}
