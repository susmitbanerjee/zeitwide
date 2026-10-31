import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";
import { parse } from "yaml";

import spec from "@/api/openapi.json";

import { anchor, operations, refName, schemaNames, typeLabel, type OpenApi } from "./openapi";

const api = spec as unknown as OpenApi;

describe("openapi.json", () => {
  it("matches api/openapi.yaml (run npm run generate:api after changing it)", () => {
    const yaml = parse(readFileSync(new URL("../../../api/openapi.yaml", import.meta.url), "utf8"));
    expect(spec).toEqual(yaml);
  });
});

describe("the generated reference", () => {
  it("lists every operation with its method, path and tag", () => {
    const all = operations(api);
    const day = all.find((operation) => operation.id === "getDay");
    expect(day).toMatchObject({
      method: "GET",
      path: "/v1/calendars/{id}/days/{date}",
      tag: "calendars",
    });
    expect(day?.parameters.map((p) => p.name)).toEqual(["id", "date", "as_of"]);
    expect(day?.authenticated).toBe(true);
    expect(all.find((o) => o.id === "getReceiptKeys")?.authenticated).toBe(false);
    expect(day?.responses.find((r) => r.status === "200")?.type).toBe("DayAnswer");
    expect(day?.responses.find((r) => r.status === "422")?.description).toContain("out_of_range");

    const decide = all.find((operation) => operation.id === "decideCandidate");
    expect(decide).toMatchObject({
      method: "POST",
      tag: "admin",
      authenticated: true,
      body: "CandidateDecision",
    });
  });

  it("labels schema types", () => {
    expect(typeLabel(undefined)).toBe("any");
    expect(typeLabel({ $ref: "#/components/schemas/Calendar" })).toBe("Calendar");
    expect(typeLabel({ type: "array", items: { $ref: "#/x/CalendarId" } })).toBe(
      "array of CalendarId",
    );
    expect(typeLabel({ type: "string", format: "date" })).toBe("string (date)");
    expect(typeLabel({ type: ["integer", "null"] })).toBe("integer or null");
    expect(typeLabel({ enum: ["a", "b"] })).toBe("a | b");
    expect(typeLabel({ oneOf: [{ $ref: "#/x/A" }, { type: "null" }] })).toBe("A or null");
    expect(typeLabel({ allOf: [{ $ref: "#/x/A" }, { $ref: "#/x/B" }] })).toBe("A and B");
    expect(typeLabel({})).toBe("object");
  });

  it("names schemas and anchors", () => {
    expect(schemaNames(api)).toContain("DayRange");
    expect(refName("#/components/schemas/Problem")).toBe("Problem");
    expect(anchor("get Day/x")).toBe("get-Day-x");
  });
});
