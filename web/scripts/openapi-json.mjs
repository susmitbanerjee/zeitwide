// Writes src/api/openapi.json from api/openapi.yaml, for the generated API reference pages.
// The Docker build only sees web/, so the reference reads this copy; a test checks it is current.
import { readFileSync, writeFileSync } from "node:fs";

import { parse } from "yaml";

const spec = parse(readFileSync(new URL("../../api/openapi.yaml", import.meta.url), "utf8"));
writeFileSync(
  new URL("../src/api/openapi.json", import.meta.url),
  `${JSON.stringify(spec, null, 2)}\n`,
);
