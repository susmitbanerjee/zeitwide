import { describe, expect, it } from "vitest";

import { reviewerEmails, rolesFor } from "./roles";

describe("reviewerEmails", () => {
  it("reads a comma-separated list, ignoring case, spaces and blanks", () => {
    expect([...reviewerEmails(" Ada@Example.com, ,bob@example.com ")]).toEqual([
      "ada@example.com",
      "bob@example.com",
    ]);
    expect(reviewerEmails(undefined).size).toBe(0);
  });
});

describe("rolesFor", () => {
  const reviewers = reviewerEmails("ada@example.com");

  it("makes a verified Google address on the list a reviewer, and nobody else", () => {
    expect(rolesFor("google", "Ada@example.com", true, reviewers)).toEqual(["reviewer"]);
    // The same address through another provider is not enough.
    expect(rolesFor("github", "ada@example.com", true, reviewers)).toEqual([]);
  });

  it("never does it for an unverified address, Microsoft, or an address not on the list", () => {
    expect(rolesFor("google", "ada@example.com", false, reviewers)).toEqual([]);
    expect(rolesFor("microsoft", "ada@example.com", true, reviewers)).toEqual([]);
    expect(rolesFor("google", "eve@example.com", true, reviewers)).toEqual([]);
    expect(rolesFor("google", null, true, reviewers)).toEqual([]);
  });
});
