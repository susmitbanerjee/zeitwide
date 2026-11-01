/**
 * Token helpers for the server. Tokens live only in Auth.js's encrypted, httpOnly session cookie
 * and in upstream API calls; nothing here ever runs in the browser.
 */

export const REVIEWER_ROLE = "reviewer";

/**
 * Keycloak's realm roles from an access token's payload. The token came straight from Keycloak's
 * token endpoint; the roles only shape the UI, and the API checks the signature and the role
 * itself on every admin request.
 */
export function rolesFromAccessToken(accessToken: string | undefined): string[] {
  const payload = accessToken?.split(".")[1];
  if (payload === undefined) {
    return [];
  }
  try {
    const claims: unknown = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    const roles = (claims as { realm_access?: { roles?: unknown } }).realm_access?.roles;
    return Array.isArray(roles)
      ? roles.filter((role): role is string => typeof role === "string")
      : [];
  } catch {
    return [];
  }
}

export interface TokenSet {
  accessToken: string;
  refreshToken: string | undefined;
  /** Seconds since the epoch, as OAuth's expires_at. */
  expiresAt: number;
}

/** Refresh this long before expiry, so a token never expires between proxy and API. */
export const REFRESH_MARGIN_SECONDS = 30;

export function needsRefresh(expiresAt: number | undefined, nowMillis: number): boolean {
  return expiresAt === undefined || nowMillis / 1000 >= expiresAt - REFRESH_MARGIN_SECONDS;
}

/** Exchanges a refresh token at Keycloak's token endpoint (a public client, so no secret). */
export async function refreshTokens(
  issuer: string,
  clientId: string,
  refreshToken: string,
  nowMillis: number,
  fetchImpl: typeof fetch = fetch,
): Promise<TokenSet> {
  const response = await fetchImpl(`${issuer}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: clientId,
      refresh_token: refreshToken,
    }),
    // Keycloak may be starting from zero: a short limit here would end the session of everyone
    // whose access token (5 minutes) ran out while it was idle.
    signal: AbortSignal.timeout(45_000),
  });
  const body: unknown = await response.json().catch(() => ({}));
  const tokens = body as { access_token?: unknown; refresh_token?: unknown; expires_in?: unknown };
  if (!response.ok || typeof tokens.access_token !== "string") {
    throw new Error(`Token refresh failed with HTTP ${response.status}`);
  }
  const expiresIn = typeof tokens.expires_in === "number" ? tokens.expires_in : 60;
  return {
    accessToken: tokens.access_token,
    refreshToken: typeof tokens.refresh_token === "string" ? tokens.refresh_token : refreshToken,
    expiresAt: Math.floor(nowMillis / 1000) + expiresIn,
  };
}

/**
 * A Cookie header with the cookies a response is about to set applied on top, so code later in the
 * same request reads the refreshed session. Cookies the response deletes are dropped.
 */
export function applySetCookies(cookieHeader: string, setCookies: string[]): string {
  const cookies = new Map<string, string>();
  for (const pair of cookieHeader.split(";")) {
    const separator = pair.indexOf("=");
    if (separator > 0) {
      cookies.set(pair.slice(0, separator).trim(), pair.slice(separator + 1).trim());
    }
  }
  for (const header of setCookies) {
    const [pair = "", ...attributes] = header.split(";");
    const separator = pair.indexOf("=");
    if (separator <= 0) {
      continue;
    }
    const name = pair.slice(0, separator).trim();
    const value = pair.slice(separator + 1).trim();
    const deleted = attributes.some((attribute) => /^\s*max-age\s*=\s*0\s*$/i.test(attribute));
    if (deleted || value === "") {
      cookies.delete(name);
    } else {
      cookies.set(name, value);
    }
  }
  return [...cookies].map(([name, value]) => `${name}=${value}`).join("; ");
}

/**
 * Keycloak's end-session URL. Clearing the app's cookie alone would leave Keycloak's SSO session
 * alive, and the next person at the same computer would be signed straight back in.
 */
export function endSessionUrl(
  issuer: string,
  clientId: string,
  idToken: string | undefined,
  postLogoutRedirect: string,
): string {
  const url = new URL(`${issuer}/protocol/openid-connect/logout`);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("post_logout_redirect_uri", postLogoutRedirect);
  if (idToken !== undefined) {
    url.searchParams.set("id_token_hint", idToken);
  }
  return url.toString();
}
