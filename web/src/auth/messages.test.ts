import { describe, expect, it } from "vitest";

import { signInProblem } from "./messages";

describe("signInProblem", () => {
  it("says nothing when there was no error", () => {
    expect(signInProblem(undefined)).toBeNull();
  });

  it("explains the cases a visitor can act on", () => {
    expect(signInProblem("OAuthAccountNotLinked")).toMatch(/different provider/);
    expect(signInProblem("AccessDenied")).toMatch(/isn't allowed/);
    expect(signInProblem(["SessionRequired", "x"])).toMatch(/session ended/);
  });

  it("gives every other code the generic message, never echoing it", () => {
    const message = signInProblem("<script>alert(1)</script>");
    expect(message).toBe("Sign-in didn't complete. Please try again.");
    expect(message).not.toMatch(/script/);
  });
});
