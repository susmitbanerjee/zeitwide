// Lighthouse SEO and accessibility on the three public templates (answer, year, compare); each
// must score 100.
//   node e2e/lighthouse.mjs [url ...]     (needs the production server from `npm run e2e`)
import { writeFileSync, mkdirSync } from "node:fs";

import { chromium } from "@playwright/test";
import lighthouse from "lighthouse";

const urls =
  process.argv.length > 2
    ? process.argv.slice(2)
    : [
        "http://localhost:3000/calendars/GB-CHAPS/2026-12-28",
        "http://localhost:3000/calendars/GB-CHAPS/2027",
        "http://localhost:3000/compare/EU-T2-vs-GB-CHAPS/2027",
      ];
const MINIMUM = 100;

const PORT = 9223;
// Playwright's own Chromium, opened for Lighthouse on a debugging port.
const browser = await chromium.launch({ args: [`--remote-debugging-port=${PORT}`] });
try {
  mkdirSync(".lighthouse", { recursive: true });
  for (const url of urls) {
    const result = await lighthouse(url, {
      port: PORT,
      output: "html",
      onlyCategories: ["seo", "accessibility"],
      logLevel: "error",
    });
    if (result === undefined) {
      throw new Error(`Lighthouse returned no result for ${url}`);
    }
    const name = new URL(url).pathname.replace(/[^a-zA-Z0-9]+/g, "-").replace(/^-|-$/g, "");
    writeFileSync(`.lighthouse/${name || "home"}.html`, result.report);
    for (const category of Object.values(result.lhr.categories)) {
      const score = Math.round((category.score ?? 0) * 100);
      console.warn(`${url} ${category.title}: ${score}`);
      if (score < MINIMUM) {
        process.exitCode = 1;
        for (const ref of category.auditRefs) {
          const audit = result.lhr.audits[ref.id];
          if (ref.weight > 0 && audit !== undefined && audit.score !== null && audit.score < 1) {
            console.warn(`  failing: ${audit.id} (${audit.title})`);
          }
        }
      }
    }
  }
} finally {
  await browser.close();
}
