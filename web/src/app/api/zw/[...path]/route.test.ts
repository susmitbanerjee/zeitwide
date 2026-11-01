import { jwtVerify } from "jose";
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const getToken = vi.fn();
vi.mock("next-auth/jwt", () => ({ getToken }));

const { GET, POST } = await import("./route");

const TOKEN_SECRET = "unit-token-secret-0123456789abcdef0123456789";
const context = (...path: string[]) => ({ params: Promise.resolve({ path }) });
const upstream = vi.fn<typeof fetch>();
const signedIn = { subject: "google:123", name: "Ada", email: "ada@example.com", roles: [] };

describe("/api/zw proxy", () => {
  beforeEach(() => {
    vi.stubEnv("AUTH_SECRET", "unit-secret");
    vi.stubEnv("ZEITWIDE_API_TOKEN_SECRET", TOKEN_SECRET);
    vi.stubGlobal("fetch", upstream);
    upstream.mockReset();
    getToken.mockReset();
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("signs a token for the signed-in person and passes the API's answer back", async () => {
    getToken.mockResolvedValue({ ...signedIn, roles: ["reviewer"] });
    upstream.mockResolvedValue(
      Response.json([], {
        headers: { "zeitwide-dataset-release": "2026.10.31.1", "set-cookie": "x=1" },
      }),
    );
    const response = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/account/api-keys?limit=5", {
        headers: { cookie: "authjs.session-token=old" },
      }),
      context("v1", "account", "api-keys"),
    );
    expect(response.status).toBe(200);
    const [url, init] = upstream.mock.calls[0]!;
    expect(url).toBe("http://localhost:8080/v1/account/api-keys?limit=5");
    const headers = new Headers(init?.headers);
    expect(headers.get("cookie")).toBeNull();
    const { payload } = await jwtVerify(
      headers.get("authorization")!.replace("Bearer ", ""),
      new TextEncoder().encode(TOKEN_SECRET),
      { issuer: "http://localhost:3000", audience: "zeitwide-api" },
    );
    expect(payload).toMatchObject({
      sub: "google:123",
      name: "Ada",
      email: "ada@example.com",
      roles: ["reviewer"],
    });
    expect(response.headers.get("zeitwide-dataset-release")).toBe("2026.10.31.1");
    expect(response.headers.get("cache-control")).toBe("no-store");
    // Nothing from the API's cookies reaches the browser, and the proxy sets none of its own:
    // a call still in flight at sign-out can't bring the session cookie back.
    expect(response.headers.getSetCookie()).toEqual([]);
  });

  it("refuses callers without a session", async () => {
    getToken.mockResolvedValue(null);
    const anonymous = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/calendars"),
      context("v1", "calendars"),
    );
    expect(anonymous.status).toBe(401);
    // A cookie that decrypts but says nobody is signed in is no session either.
    getToken.mockResolvedValue({ name: "Nobody" });
    const nobody = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/calendars"),
      context("v1", "calendars"),
    );
    expect(nobody.status).toBe(401);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("won't call the API without the secret to sign with", async () => {
    vi.stubEnv("ZEITWIDE_API_TOKEN_SECRET", "");
    getToken.mockResolvedValue(signedIn);
    const response = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/calendars"),
      context("v1", "calendars"),
    );
    expect(response.status).toBe(500);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("forwards only /v1 routes", async () => {
    const response = await GET(
      new NextRequest("http://localhost:3000/api/zw/internal/x"),
      context("internal", "x"),
    );
    expect(response.status).toBe(404);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("refuses cross-site writes and oversized bodies", async () => {
    const forged = await POST(
      new NextRequest("http://localhost:3000/api/zw/v1/account/api-keys", {
        method: "POST",
        headers: { origin: "https://evil.example" },
        body: "{}",
      }),
      context("v1", "account", "api-keys"),
    );
    expect(forged.status).toBe(403);
    const big = await POST(
      new NextRequest("http://localhost:3000/api/zw/v1/account/api-keys", {
        method: "POST",
        headers: { origin: "http://localhost:3000", "content-type": "application/json" },
        body: "x".repeat(70_000),
      }),
      context("v1", "account", "api-keys"),
    );
    expect(big.status).toBe(413);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("reports an unreachable API as a problem", async () => {
    getToken.mockResolvedValue(signedIn);
    upstream.mockRejectedValue(new TypeError("fetch failed"));
    const response = await POST(
      new NextRequest("http://localhost:3000/api/zw/v1/business-days/check", {
        method: "POST",
        headers: { origin: "http://localhost:3000", "content-type": "application/json" },
        body: "{}",
      }),
      context("v1", "business-days", "check"),
    );
    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ code: "internal_error" });
  });
});
