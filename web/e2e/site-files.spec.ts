import { execFileSync } from "node:child_process";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { BASE_URL, MOCK_API_URL } from "../playwright.config";

/**
 * The pipeline's validator against this site: llms.txt structure and links, llms-full.txt
 * closure counts against the API, and the sitemaps against the sitemaps.org schemas.
 * ZEITWIDE_PIPELINE_PYTHON names the pipeline's Python; by default `uv run` finds it.
 */
test("llms.txt, llms-full.txt and the sitemaps pass the pipeline's validator", () => {
  test.setTimeout(5 * 60_000);
  const python = process.env.ZEITWIDE_PIPELINE_PYTHON;
  const [command, args] =
    python === undefined
      ? ["uv", ["run", "--no-sync", "python", "-m", "zeitwide_pipeline"]]
      : [python, ["-m", "zeitwide_pipeline"]];
  const output = execFileSync(
    command,
    [...args, "validate-site", "--site-url", BASE_URL, "--api-url", MOCK_API_URL],
    { cwd: join(__dirname, "..", "..", "pipeline"), encoding: "utf8" },
  );
  expect(output).toContain("ok: llms.txt, llms-full.txt and the sitemaps are valid");
});
