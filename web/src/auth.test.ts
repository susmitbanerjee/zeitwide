import type { NextAuthConfig } from "next-auth";
import type { JWT } from "next-auth/jwt";
import { beforeEach, describe, expect, it, vi } from "vitest";

const config = vi.hoisted(() => ({ current: undefined as NextAuthConfig | undefined }));
const cookieValues = vi.hoisted(() => new Map<string, string>());

vi.mock("next-auth", () => ({
  default: (options: NextAuthConfig) => {
    config.current = options;
    return { handlers: {}, auth: vi.fn(), signIn: vi.fn(), signOut: vi.fn() };
  },
}));
vi.mock("next/headers", () => ({
  cookies: () =>
    Promise.resolve({
      get: (name: string) => {
        const value = cookieValues.get(name);
        return value === undefined ? undefined : { name, value };
      },
    }),
}));

await import("./auth");

type JwtCallback = (params: {
  token: JWT;
  account?: { provider: string; providerAccountId: string } | null;
  user?: { id?: string; email?: string | null };
  profile?: unknown;
}) => Promise<JWT>;

const jwt = config.current!.callbacks!.jwt as unknown as JwtCallback;

function signIn(token: JWT = {}): Promise<JWT> {
  return jwt({
    token,
    account: { provider: "github", providerAccountId: "42" },
    user: { id: "42", email: "ada@example.com" },
  });
}

describe("the session token at sign-in", () => {
  beforeEach(() => {
    cookieValues.clear();
  });

  it("keeps the AI referral the edge Worker tagged", async () => {
    cookieValues.set("zw_ref", "ai:openai");
    expect(await signIn()).toMatchObject({ subject: "github:42", signupSource: "ai:openai" });
  });

  it("has no signup source without the cookie", async () => {
    expect(await signIn()).not.toHaveProperty("signupSource");
  });

  it("drops a forged cookie, and forgets an earlier source with it", async () => {
    cookieValues.set("zw_ref", "ai:openai; email=ada@example.com");
    expect(await signIn({ signupSource: "ai:google" })).not.toHaveProperty("signupSource");
  });

  it("leaves the token alone between sign-ins", async () => {
    cookieValues.set("zw_ref", "ai:anthropic");
    const token = { subject: "github:42", roles: [] };
    expect(await jwt({ token })).toEqual(token);
  });
});
