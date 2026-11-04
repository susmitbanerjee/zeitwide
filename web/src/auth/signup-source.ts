/**
 * The AI referral a sign-up came from. The Cloudflare edge Worker
 * (deploy/cloudflare/edge/src/referral.ts) sets the first-party `zw_ref` cookie to `ai:<vendor>`
 * on page views an AI assistant referred; sign-in passes it on so the API can store it on a new
 * account. It names a vendor only, never a person. The cookie is client input, so anything but an
 * exact known value is dropped.
 */

export const REFERRAL_COOKIE = "zw_ref";

const SIGNUP_SOURCE = /^ai:(openai|perplexity|anthropic|google|microsoft)$/;

export type SignupSource = `ai:${"openai" | "perplexity" | "anthropic" | "google" | "microsoft"}`;

/** The signup source a value names exactly, or undefined for anything else. */
export function signupSourceFrom(value: unknown): SignupSource | undefined {
  return typeof value === "string" && SIGNUP_SOURCE.test(value)
    ? (value as SignupSource)
    : undefined;
}

interface CookieReader {
  get(name: string): { value: string } | undefined;
}

/** The signup source in the referral cookie; undefined when there's none or it's forged. */
export function signupSourceFromCookies(cookies: CookieReader): SignupSource | undefined {
  return signupSourceFrom(cookies.get(REFERRAL_COOKIE)?.value);
}
