import { describe, expect, it } from "vitest";

import { identityProviderHint } from "./identity-providers";

describe("identityProviderHint", () => {
  it("sends Google and Microsoft straight to their provider", () => {
    expect(identityProviderHint("google")).toEqual({ kc_idp_hint: "google" });
    expect(identityProviderHint("microsoft")).toEqual({ kc_idp_hint: "microsoft" });
  });

  it("gives no hint for anything else", () => {
    expect(identityProviderHint(null)).toEqual({});
    expect(identityProviderHint("")).toEqual({});
    expect(identityProviderHint("github")).toEqual({});
    expect(identityProviderHint("google&prompt=none")).toEqual({});
  });
});
