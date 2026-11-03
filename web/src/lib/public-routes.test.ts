import { describe, expect, it } from "vitest";

import { markdownUrl, matchPublicPath } from "./public-routes";

describe("public routes", () => {
  it("names the page type for each public path", () => {
    expect(matchPublicPath("/calendars")).toEqual({ type: "calendars" });
    expect(matchPublicPath("/calendars/GB-CHAPS")).toEqual({ type: "calendar", id: "GB-CHAPS" });
    expect(matchPublicPath("/calendars/GB-CHAPS/2027")).toEqual({
      type: "year",
      id: "GB-CHAPS",
      year: 2027,
    });
    expect(matchPublicPath("/calendars/GB-CHAPS/2026-12-28")).toEqual({
      type: "answer",
      id: "GB-CHAPS",
      date: "2026-12-28",
    });
    expect(matchPublicPath("/compare/EU-T2-vs-GB-CHAPS/2027")).toEqual({
      type: "compare",
      pair: "EU-T2-vs-GB-CHAPS",
      year: 2027,
    });
    expect(matchPublicPath("/sources")).toEqual({ type: "sources" });
    expect(matchPublicPath("/pricing/")).toEqual({ type: "pricing" });
    expect(matchPublicPath("/docs")).toEqual({ type: "doc", slug: "index" });
    expect(matchPublicPath("/docs/rules")).toEqual({ type: "doc", slug: "rules" });
  });

  it("matches nothing else", () => {
    for (const path of [
      "/",
      "/docs/a/b",
      "/calendars/GB-CHAPS/nope",
      "/calendars/a/b/c",
      "/compare/a-vs-b/x",
      "/sources/x",
      "/calendars/%E0%A4%A",
    ]) {
      expect(matchPublicPath(path)).toBeNull();
    }
  });

  it("puts the Markdown next to the canonical page", () => {
    expect(markdownUrl("https://zeitwide.example/calendars/GB-CHAPS/2026-12-28")).toBe(
      "https://zeitwide.example/calendars/GB-CHAPS/2026-12-28.md",
    );
  });
});
