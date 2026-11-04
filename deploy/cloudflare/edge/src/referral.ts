/** AI assistants whose referrals we tag, by the host people click through from. */
const REFERRER_HOSTS: readonly (readonly [host: string, vendor: string])[] = [
  ["chatgpt.com", "openai"],
  ["perplexity.ai", "perplexity"],
  ["claude.ai", "anthropic"],
  ["gemini.google.com", "google"],
  ["copilot.microsoft.com", "microsoft"],
];

/** utm_source values that name one of them (ChatGPT adds utm_source=chatgpt.com itself). */
const UTM_SOURCES: ReadonlyMap<string, string> = new Map([
  ...REFERRER_HOSTS,
  ["chatgpt", "openai"],
  ["openai", "openai"],
  ["perplexity", "perplexity"],
  ["claude", "anthropic"],
  ["anthropic", "anthropic"],
  ["gemini", "google"],
  ["copilot", "microsoft"],
]);

export const REFERRAL_COOKIE = "zw_ref";
const THIRTY_DAYS_S = 30 * 24 * 60 * 60;

/** The AI vendor a visit came from, by Referer host or utm_source; null when neither names one. */
export function aiReferral(referer: string | null, url: URL): string | null {
  const host = refererHost(referer);
  if (host !== null) {
    const match = REFERRER_HOSTS.find(([domain]) => host === domain || host.endsWith(`.${domain}`));
    if (match !== undefined) {
      return match[1];
    }
  }
  const utm = url.searchParams.get("utm_source")?.trim().toLowerCase();
  return utm === undefined ? null : (UTM_SOURCES.get(utm) ?? null);
}

/**
 * The first-party cookie that lets sign-up attribute the visit: `ai:<vendor>` and nothing else,
 * for 30 days. HttpOnly, because only the server reads it at sign-up. The latest AI referral wins.
 */
export function referralCookie(vendor: string): string {
  return `${REFERRAL_COOKIE}=ai:${vendor}; Max-Age=${THIRTY_DAYS_S}; Path=/; Secure; HttpOnly; SameSite=Lax`;
}

function refererHost(referer: string | null): string | null {
  if (referer === null) {
    return null;
  }
  try {
    return new URL(referer).hostname.toLowerCase();
  } catch {
    return null;
  }
}
