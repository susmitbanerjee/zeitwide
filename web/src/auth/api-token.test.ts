import { jwtVerify } from "jose";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/env", () => ({ env: { appUrl: "https://zeitwide.com" } }));

const { signApiToken } = await import("./api-token");
const secret = "unit-test-secret-0123456789abcdef0123456789";

describe("signApiToken", () => {
  it("signs who the caller is, their roles and a one-minute lifetime for the API", async () => {
    const token = await signApiToken(
      { subject: "google:123", name: "Ada", email: "ada@example.com", roles: ["reviewer"] },
      secret,
      "https://zeitwide.com",
      1_000_000,
    );
    const { payload, protectedHeader } = await jwtVerify(token, new TextEncoder().encode(secret), {
      issuer: "https://zeitwide.com",
      audience: "zeitwide-api",
      currentDate: new Date(1_000_030_000),
    });
    expect(protectedHeader.alg).toBe("HS256");
    expect(payload).toMatchObject({
      sub: "google:123",
      name: "Ada",
      email: "ada@example.com",
      roles: ["reviewer"],
      iat: 1_000_000,
      exp: 1_000_060,
    });
  });

  it("can't be verified with another secret", async () => {
    const token = await signApiToken(
      { subject: "x", name: undefined, email: undefined, roles: [] },
      secret,
    );
    await expect(
      jwtVerify(token, new TextEncoder().encode("another-secret-0123456789abcdef01234567")),
    ).rejects.toThrow();
  });
});
