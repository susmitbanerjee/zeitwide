/**
 * Fills the RANGES KV namespace once, before the first daily cron, so verified AI fetchers are
 * recognised from the moment the zone goes live. Runs the Worker's own refresh code on this
 * machine and writes a bulk file; `npm run seed-ranges -- <namespace id>` uploads it with wrangler.
 */
import { writeFileSync } from "node:fs";

import { refreshRanges } from "../src/ranges";

const out = process.argv[2] ?? "dist/ranges-bulk.json";
const entries: { key: string; value: string }[] = [];
const kv = {
  async put(key: string, value: string) {
    entries.push({ key, value });
  },
} as unknown as KVNamespace;

const results = await refreshRanges(kv);
for (const result of results) {
  console.log(
    result.ok ? `ok    ${result.id}: ${result.count}` : `FAIL  ${result.id}: ${result.error}`,
  );
}
if (entries.length === 0) {
  console.error("no range list could be fetched");
  process.exit(1);
}
writeFileSync(out, JSON.stringify(entries));
console.log(`wrote ${entries.length} lists to ${out}`);
