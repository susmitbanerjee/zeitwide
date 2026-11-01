import { describe, expect, it } from "vitest";

import { safeCallback } from "./callback";

describe("safeCallback", () => {
  it("keeps a same-site path, with its query", () => {
    expect(safeCallback("/app/changes?x=1", "/app")).toBe("/app/changes?x=1");
    expect(safeCallback(["/app/explorer", "/other"], "/app")).toBe("/app/explorer");
  });

  it("falls back for anything that could leave the site", () => {
    for (const value of [undefined, "", "app", "//evil.example", "https://evil.example/", 42]) {
      expect(safeCallback(value, "/app")).toBe("/app");
    }
  });
});
