import { createHmac } from "node:crypto";

import { describe, expect, it } from "vitest";

import { hasSessionCookie, hostRedirect, isPrivatePath, needsSession } from "./routing";
import { contentSecurityPolicy, SECURITY_HEADERS } from "./security-headers";
import {
  FORWARDED_REQUEST_HEADERS,
  isForwardedResponseHeader,
  isTrustedOrigin,
  upstreamPath,
} from "./upstream";
import { changedCalendar, verifyWebhookSignature } from "./webhook-signature";

describe("contentSecurityPolicy", () => {
  it("is strict with a nonce on signed-in pages", () => {
    const policy = contentSecurityPolicy({ nonce: "abc" });
    expect(policy).toContain("script-src 'self' 'nonce-abc' 'strict-dynamic'");
    expect(policy).not.toContain("unsafe-inline' ;");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("base-uri 'none'");
    expect(policy).toContain("upgrade-insecure-requests");
  });

  it("allows only same-origin scripts on prerendered pages, and eval only in development", () => {
    expect(contentSecurityPolicy({})).toContain("script-src 'self' 'unsafe-inline';");
    const dev = contentSecurityPolicy({ development: true });
    expect(dev).toContain("'unsafe-eval'");
    expect(dev).not.toContain("upgrade-insecure-requests");
    expect(contentSecurityPolicy({ https: false })).not.toContain("upgrade-insecure-requests");
  });

  it("ships the other security headers", () => {
    expect(SECURITY_HEADERS.map((h) => h.key)).toEqual(
      expect.arrayContaining([
        "Strict-Transport-Security",
        "X-Content-Type-Options",
        "Referrer-Policy",
      ]),
    );
  });
});

describe("routing", () => {
  it("knows which paths need the app host and a session", () => {
    expect(isPrivatePath("/app/explorer")).toBe(true);
    expect(isPrivatePath("/api/zw/v1/calendars")).toBe(true);
    expect(isPrivatePath("/applesauce")).toBe(false);
    expect(isPrivatePath("/calendars/GB-CHAPS/2026-12-28")).toBe(false);
    expect(needsSession("/admin")).toBe(true);
    expect(needsSession("/signin")).toBe(false);
    // Sign-up starts a session on the app host, so it lives there too, and needs none.
    expect(isPrivatePath("/signup")).toBe(true);
    expect(needsSession("/signup")).toBe(false);
  });

  it("sends signed-in paths on the public host to the app host only", () => {
    const site = "https://zeitwide.com";
    const app = "https://app.zeitwide.com";
    expect(hostRedirect("zeitwide.com", "/app/explorer", "?x=1", site, app)).toBe(
      "https://app.zeitwide.com/app/explorer?x=1",
    );
    expect(hostRedirect("app.zeitwide.com", "/app/explorer", "", site, app)).toBeNull();
    expect(hostRedirect("zeitwide.com", "/calendars", "", site, app)).toBeNull();
    expect(
      hostRedirect("localhost:3000", "/app", "", "http://localhost:3000", "http://localhost:3000"),
    ).toBeNull();
  });

  it("recognises Auth.js session cookies, chunked or secure", () => {
    expect(hasSessionCookie(["authjs.session-token"])).toBe(true);
    expect(hasSessionCookie(["__Secure-authjs.session-token.0"])).toBe(true);
    expect(hasSessionCookie(["authjs.csrf-token", "other"])).toBe(false);
  });
});

describe("the API proxy", () => {
  it("forwards only /v1 routes with plain segments", () => {
    expect(upstreamPath(["v1", "calendars", "GB-CHAPS", "days"])).toBe(
      "/v1/calendars/GB-CHAPS/days",
    );
    expect(upstreamPath(["internal", "outbox", "drain"])).toBeNull();
    expect(upstreamPath(["v1"])).toBeNull();
    expect(upstreamPath(["v1", ".."])).toBeNull();
    expect(upstreamPath(["v1", "a%2Fb"])).toBeNull();
  });

  it("passes the API's headers back but never cookies", () => {
    expect(isForwardedResponseHeader("Zeitwide-Dataset-Release")).toBe(true);
    expect(isForwardedResponseHeader("RateLimit-Remaining")).toBe(true);
    expect(isForwardedResponseHeader("content-type")).toBe(true);
    expect(isForwardedResponseHeader("set-cookie")).toBe(false);
    expect(FORWARDED_REQUEST_HEADERS).not.toContain("cookie");
  });

  it("refuses cross-site writes", () => {
    const app = "https://app.zeitwide.com";
    expect(isTrustedOrigin("POST", app, "same-origin", app)).toBe(true);
    expect(isTrustedOrigin("POST", app, null, app)).toBe(true);
    expect(isTrustedOrigin("POST", "https://evil.example", "cross-site", app)).toBe(false);
    expect(isTrustedOrigin("DELETE", null, null, app)).toBe(false);
    expect(isTrustedOrigin("GET", null, null, app)).toBe(true);
    expect(isTrustedOrigin("GET", null, "cross-site", app)).toBe(false);
  });
});

describe("verifyWebhookSignature", () => {
  const secret = "whsec_test";
  const body = '{"data":{"calendar_id":"GB-CHAPS"}}';
  const sign = (t: number) =>
    `t=${t},v1=${createHmac("sha256", secret).update(`${t}.${body}`).digest("hex")}`;

  it("accepts a fresh, correct signature", () => {
    expect(verifyWebhookSignature(sign(1000), body, secret, 1010)).toBe(true);
  });

  it("refuses stale, tampered, malformed or unsigned deliveries", () => {
    expect(verifyWebhookSignature(sign(1000), body, secret, 1400)).toBe(false);
    expect(verifyWebhookSignature(sign(1000), `${body} `, secret, 1000)).toBe(false);
    expect(verifyWebhookSignature("t=1000,v1=zz", body, secret, 1000)).toBe(false);
    expect(verifyWebhookSignature("v1=abc", body, secret, 1000)).toBe(false);
    expect(verifyWebhookSignature(null, body, secret, 1000)).toBe(false);
    expect(verifyWebhookSignature(sign(1000), body, "", 1000)).toBe(false);
  });

  it("reads the changed calendar from the envelope", () => {
    expect(changedCalendar(JSON.parse(body))).toBe("GB-CHAPS");
    expect(changedCalendar({ data: { calendar_id: "../x" } })).toBeNull();
    expect(changedCalendar(null)).toBeNull();
  });
});
