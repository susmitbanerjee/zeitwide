/**
 * What to tell a visitor when Auth.js sends them back to the sign-in page with `?error=<code>`.
 * The codes are Auth.js's; none of them reveals which part of the flow failed or why.
 */
const PROBLEMS: Record<string, string> = {
  AccessDenied: "That account isn't allowed to sign in. Try a different one.",
  OAuthAccountNotLinked:
    "That email is already signed up with a different provider. Use the provider you first signed in with.",
  Configuration: "Sign-in isn't set up properly yet. Please try again shortly.",
  SessionRequired: "Your session ended. Sign in again to continue.",
};

const FALLBACK = "Sign-in didn't complete. Please try again.";

/** The message for an error code, or null when there was no error. Unknown codes get a generic one. */
export function signInProblem(code: string | string[] | undefined): string | null {
  const value = Array.isArray(code) ? code[0] : code;
  if (value === undefined) {
    return null;
  }
  return PROBLEMS[value] ?? FALLBACK;
}
