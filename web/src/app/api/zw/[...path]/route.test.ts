import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.fn();
const getToken = vi.fn();
vi.mock("@/auth", () => ({ auth }));
vi.mock("next-auth/jwt", () => ({ getToken }));

const { GET, POST } = await import("./route");

const context = (...path: string[]) => ({ params: Promise.resolve({ path }) });
const upstream = vi.fn<typeof fetch>();

describe("/api/zw proxy", () => {
  beforeEach(() => {
    vi.stubEnv("AUTH_SECRET", "unit-secret");
    vi.stubGlobal("fetch", upstream);
    upstream.mockReset();
    auth.mockReset();
    getToken.mockReset();
    auth.mockImplementation(({ res }: { res: { headers: Headers } }) => {
      res.headers.append("set-cookie", "authjs.session-token=refreshed; Path=/; HttpOnly");
      return Promise.resolve(null);
    });
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("adds the session's access token and passes the API's answer back", async () => {
    // Read twice: before the refresh (an expired token) and after it.
    getToken
      .mockResolvedValueOnce({ accessToken: "expired" })
      .mockResolvedValueOnce({ accessToken: "access-123" });
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
    expect(new Headers(init?.headers).get("authorization")).toBe("Bearer access-123");
    expect(new Headers(init?.headers).get("cookie")).toBeNull();
    expect(response.headers.get("zeitwide-dataset-release")).toBe("2026.10.31.1");
    expect(response.headers.get("cache-control")).toBe("no-store");
    // The refreshed session cookie goes back to the browser; the API's cookies never do.
    expect(response.headers.getSetCookie()).toEqual([
      "authjs.session-token=refreshed; Path=/; HttpOnly",
    ]);
    expect(getToken.mock.calls[1]?.[0].req.headers.cookie).toBe("authjs.session-token=refreshed");
  });

  it("doesn't re-issue the session cookie when nothing was refreshed", async () => {
    // A call still in flight at sign-out must not bring back the cookie sign-out deleted.
    getToken.mockResolvedValue({ accessToken: "access-123" });
    upstream.mockResolvedValue(Response.json([]));
    const response = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/calendars", {
        headers: { cookie: "authjs.session-token=current" },
      }),
      context("v1", "calendars"),
    );
    expect(response.status).toBe(200);
    expect(response.headers.getSetCookie()).toEqual([]);
  });

  it("refuses callers without a session or after a failed refresh", async () => {
    getToken.mockResolvedValue({ accessToken: "stale", error: "RefreshTokenError" });
    const expired = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/calendars"),
      context("v1", "calendars"),
    );
    expect(expired.status).toBe(401);
    getToken.mockResolvedValue(null);
    const anonymous = await GET(
      new NextRequest("http://localhost:3000/api/zw/v1/calendars"),
      context("v1", "calendars"),
    );
    expect(anonymous.status).toBe(401);
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
    getToken.mockResolvedValue({ accessToken: "access-123" });
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
