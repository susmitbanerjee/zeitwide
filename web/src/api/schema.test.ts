import { readFileSync } from "node:fs";

import openapiTS, { astToString, COMMENT_HEADER } from "openapi-typescript";
import { describe, expect, it } from "vitest";

const SPEC = new URL("../../../api/openapi.yaml", import.meta.url);
const SCHEMA = new URL("./schema.ts", import.meta.url);

describe("generated API types", () => {
  it("match api/openapi.yaml (run npm run generate:api after changing it)", async () => {
    const generated = COMMENT_HEADER + astToString(await openapiTS(SPEC));

    expect(readFileSync(SCHEMA, "utf8")).toBe(generated);
  });
});
