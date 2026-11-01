/** Only same-site paths, so the sign-in pages can't be used to redirect anywhere else. */
export function safeCallback(value: unknown, fallback: string): string {
  const path = Array.isArray(value) ? value[0] : value;
  return typeof path === "string" && path.startsWith("/") && !path.startsWith("//")
    ? path
    : fallback;
}

/** Where a visitor lands after signing in or up when the page didn't say: keys for a new account. */
export function defaultLanding(pathname: string): string {
  return pathname === "/signup" ? "/app/developers" : "/app";
}
