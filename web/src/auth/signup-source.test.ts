import { describe, expect, it } from "vitest";

import { REFERRAL_COOKIE, signupSourceFrom, signupSourceFromCookies } from "./signup-source";

function cookies(values: Record<string, string>) {
  return {
    get: (name: string) => (name in values ? { value: values[name] ?? "" } : undefined),
  };
}

describe("signupSourceFrom", () => {
  it.each(["openai", "perplexity", "anthropic", "google", "microsoft"])("keeps ai:%s", (vendor) => {
    expect(signupSourceFrom(`ai:${vendor}`)).toBe(`ai:${vendor}`);
  });

  it.each([
    "",
    "openai",
    "ai:",
    "ai:OpenAI",
    "AI:openai",
    " ai:openai",
    "ai:openai ",
    "ai:openai\n",
    "ai:openai;x=1",
    "ai:openai,ai:google",
    "ai:example",
    "ai%3Aopenai",
    "user:ada@example.com",
  ])("drops the forged value %j", (forged) => {
    expect(signupSourceFrom(forged)).toBeUndefined();
  });

  it.each([undefined, null, 7, ["ai:openai"], { vendor: "openai" }])(
    "drops the non-string %j",
    (value) => {
      expect(signupSourceFrom(value)).toBeUndefined();
    },
  );
});

describe("signupSourceFromCookies", () => {
  it("reads the edge Worker's referral cookie", () => {
    expect(signupSourceFromCookies(cookies({ [REFERRAL_COOKIE]: "ai:anthropic" }))).toBe(
      "ai:anthropic",
    );
  });

  it("is undefined without the cookie", () => {
    expect(signupSourceFromCookies(cookies({ other: "ai:anthropic" }))).toBeUndefined();
  });

  it("is undefined when the cookie is forged", () => {
    expect(signupSourceFromCookies(cookies({ [REFERRAL_COOKIE]: "ai:evil" }))).toBeUndefined();
  });
});
