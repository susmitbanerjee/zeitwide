// Writes the fake API's data as a release document, for `zeitwide-pipeline site-files`:
//   node e2e/mock-api/write-release.mjs [out]   (default e2e/.site-files/release.json)
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

import { releaseDocument } from "./data.mjs";

const out = process.argv[2] ?? "e2e/.site-files/release.json";
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, `${JSON.stringify(releaseDocument(), null, 2)}\n`);
console.warn(`Wrote ${out}`);
