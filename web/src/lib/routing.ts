/**
 * Which host serves what. Public pages (answers, docs) go through Firebase Hosting's CDN, which
 * forwards no cookies but __session, so everything that needs a session lives on the app host,
 * served straight from Cloud Run. Locally both are the same origin.
 */

const PRIVATE_PREFIXES = ["/app", "/admin", "/signin", "/signup", "/api/auth", "/api/zw"] as const;

export function isPrivatePath(pathname: string): boolean {
  return PRIVATE_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}

/** Pages that need a signed-in user; /signin, /signup and the auth routes themselves don't. */
export function needsSession(pathname: string): boolean {
  return ["/app", "/admin"].some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}

/** Where to send a request that arrived on the wrong host, or null to serve it here. */
export function hostRedirect(
  requestHost: string,
  pathname: string,
  search: string,
  siteOrigin: string,
  appOrigin: string,
): string | null {
  const appHost = new URL(appOrigin).host;
  if (appHost === new URL(siteOrigin).host) {
    return null;
  }
  if (isPrivatePath(pathname) && requestHost !== appHost) {
    return `${appOrigin}${pathname}${search}`;
  }
  return null;
}

/** Auth.js's session cookie, plain or chunked, with or without the __Secure- prefix. */
export function hasSessionCookie(cookieNames: string[]): boolean {
  return cookieNames.some((name) => /^(__Secure-)?authjs\.session-token(\.\d+)?$/.test(name));
}
