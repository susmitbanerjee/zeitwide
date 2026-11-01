import { describe, expect, it } from "vitest";

import {
  configuredProviders,
  providerFrom,
  testLoginEnabled,
  testUserFrom,
} from "./identity-providers";

describe("configuredProviders", () => {
  it("offers only the providers whose client ID is set", () => {
    expect(configuredProviders({}).map((p) => p.id)).toEqual([]);
    expect(
      configuredProviders({ AUTH_GOOGLE_ID: "g", AUTH_GITHUB_ID: " ", AUTH_MICROSOFT_ID: "m" }).map(
        (p) => p.id,
      ),
    ).toEqual(["google", "microsoft"]);
  });
});

describe("providerFrom", () => {
  it("passes a known provider and nothing else", () => {
    expect(providerFrom("google")).toBe("google");
    expect(providerFrom("github")).toBe("github");
    expect(providerFrom("keycloak")).toBeNull();
    expect(providerFrom("google&prompt=none")).toBeNull();
    expect(providerFrom(null)).toBeNull();
  });
});

describe("test sign-in", () => {
  it("is on only when asked for, and only on localhost", () => {
    expect(testLoginEnabled({})).toBe(false);
    expect(testLoginEnabled({ ZEITWIDE_TEST_LOGIN: "1" })).toBe(true);
    expect(testLoginEnabled({ ZEITWIDE_TEST_LOGIN: "1", AUTH_URL: "http://127.0.0.1:3000" })).toBe(
      true,
    );
    // Left on by mistake in a deployed environment: still off.
    expect(testLoginEnabled({ ZEITWIDE_TEST_LOGIN: "1", AUTH_URL: "https://zeitwide.com" })).toBe(
      false,
    );
    expect(testLoginEnabled({ ZEITWIDE_TEST_LOGIN: "true" })).toBe(false);
    expect(testLoginEnabled({ ZEITWIDE_TEST_LOGIN: "1", AUTH_URL: "not a url" })).toBe(false);
  });

  it("knows two users", () => {
    expect(testUserFrom("member")).toBe("member");
    expect(testUserFrom("reviewer")).toBe("reviewer");
    expect(testUserFrom("admin")).toBeNull();
  });
});
