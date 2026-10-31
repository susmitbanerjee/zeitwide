import { describe, expect, it, vi } from "vitest";

import {
  applySetCookies,
  endSessionUrl,
  needsRefresh,
  refreshTokens,
  REFRESH_MARGIN_SECONDS,
  rolesFromAccessToken,
} from "./tokens";

const token = (claims: unknown) =>
  `header.${Buffer.from(JSON.stringify(claims)).toString("base64url")}.signature`;

describe("rolesFromAccessToken", () => {
  it("reads Keycloak realm roles", () => {
    expect(rolesFromAccessToken(token({ realm_access: { roles: ["reviewer", 7] } }))).toEqual([
      "reviewer",
    ]);
  });

  it("finds no roles in anything else", () => {
    expect(rolesFromAccessToken(undefined)).toEqual([]);
    expect(rolesFromAccessToken("opaque")).toEqual([]);
    expect(rolesFromAccessToken("a.!!!.c")).toEqual([]);
    expect(rolesFromAccessToken(token({ sub: "x" }))).toEqual([]);
  });
});

describe("needsRefresh", () => {
  it("refreshes shortly before expiry", () => {
    expect(needsRefresh(1000, (1000 - REFRESH_MARGIN_SECONDS - 1) * 1000)).toBe(false);
    expect(needsRefresh(1000, (1000 - REFRESH_MARGIN_SECONDS) * 1000)).toBe(true);
    expect(needsRefresh(undefined, 0)).toBe(true);
  });
});

describe("refreshTokens", () => {
  it("exchanges the refresh token as a public client", async () => {
    const fetchMock = vi.fn<typeof fetch>(() =>
      Promise.resolve(
        Response.json({
          access_token: "new-access",
          refresh_token: "new-refresh",
          expires_in: 300,
        }),
      ),
    );
    const tokens = await refreshTokens(
      "https://kc/realms/z",
      "zeitwide-web",
      "old",
      1_000_000,
      fetchMock,
    );
    expect(tokens).toEqual({
      accessToken: "new-access",
      refreshToken: "new-refresh",
      expiresAt: 1300,
    });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("https://kc/realms/z/protocol/openid-connect/token");
    expect(String(init?.body)).toBe(
      "grant_type=refresh_token&client_id=zeitwide-web&refresh_token=old",
    );
  });

  it("keeps the old refresh token when Keycloak sends none", async () => {
    const fetchMock = vi.fn<typeof fetch>(() =>
      Promise.resolve(Response.json({ access_token: "a" })),
    );
    const tokens = await refreshTokens("https://kc", "c", "old", 0, fetchMock);
    expect(tokens.refreshToken).toBe("old");
    expect(tokens.expiresAt).toBe(60);
  });

  it("fails when Keycloak refuses", async () => {
    const fetchMock = vi.fn<typeof fetch>(() =>
      Promise.resolve(Response.json({ error: "invalid_grant" }, { status: 400 })),
    );
    await expect(refreshTokens("https://kc", "c", "old", 0, fetchMock)).rejects.toThrow("HTTP 400");
  });
});

describe("applySetCookies", () => {
  it("overlays new cookies and drops deleted ones", () => {
    expect(
      applySetCookies("a=1; authjs.session-token=old; b=2", [
        "authjs.session-token=new; Path=/; HttpOnly",
        "b=; Max-Age=0",
        "junk",
      ]),
    ).toBe("a=1; authjs.session-token=new");
  });
});

describe("endSessionUrl", () => {
  it("ends the Keycloak session and comes back to the app", () => {
    const url = new URL(
      endSessionUrl("https://kc/realms/z", "zeitwide-web", "id.jwt", "https://app.zeitwide.com/"),
    );
    expect(url.origin + url.pathname).toBe("https://kc/realms/z/protocol/openid-connect/logout");
    expect(url.searchParams.get("id_token_hint")).toBe("id.jwt");
    expect(url.searchParams.get("client_id")).toBe("zeitwide-web");
    expect(url.searchParams.get("post_logout_redirect_uri")).toBe("https://app.zeitwide.com/");
  });

  it("still ends the session without an ID token", () => {
    expect(endSessionUrl("https://kc", "c", undefined, "https://a/")).not.toContain(
      "id_token_hint",
    );
  });
});
