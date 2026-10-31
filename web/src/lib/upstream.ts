/**
 * Rules for /api/zw, the same-origin path the browser uses to reach the Zeitwide API. The proxy
 * attaches the signed-in user's access token from the session cookie, so tokens never reach
 * browser JavaScript, and it forwards nothing but the API's own versioned routes.
 */

const SEGMENT = /^[A-Za-z0-9._~-]+$/;

/** The API path for proxy path segments, or null when it isn't a /v1 route. */
export function upstreamPath(segments: readonly string[]): string | null {
  if (segments[0] !== "v1" || segments.length < 2) {
    return null;
  }
  if (!segments.every((segment) => SEGMENT.test(segment) && segment !== "." && segment !== "..")) {
    return null;
  }
  return `/${segments.join("/")}`;
}

export const FORWARDED_METHODS = ["GET", "POST", "PUT", "DELETE"] as const;
export type ForwardedMethod = (typeof FORWARDED_METHODS)[number];

/** Request headers the API needs; everything else (cookies above all) stays behind. */
export const FORWARDED_REQUEST_HEADERS = ["accept", "content-type", "idempotency-key"] as const;

/** Response headers worth passing back: the release and revision headers, rate limits. */
export function isForwardedResponseHeader(name: string): boolean {
  const lower = name.toLowerCase();
  return (
    lower === "content-type" ||
    lower === "retry-after" ||
    lower.startsWith("zeitwide-") ||
    lower.startsWith("ratelimit-")
  );
}

/** Requests bigger than this are refused before they reach the API. */
export const MAX_BODY_BYTES = 64 * 1024;

/**
 * Cross-site request forgery guard for state-changing calls: the browser must say the request
 * comes from the app's own origin. The session cookie is also SameSite=Lax.
 */
export function isTrustedOrigin(
  method: string,
  originHeader: string | null,
  secFetchSite: string | null,
  appOrigin: string,
): boolean {
  if (method === "GET") {
    return secFetchSite === null || secFetchSite !== "cross-site";
  }
  return originHeader === appOrigin && (secFetchSite === null || secFetchSite === "same-origin");
}
