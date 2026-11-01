/**
 * The ways to sign in. Zeitwide has no passwords and no identity server of its own: the web app
 * talks to Google, Microsoft and GitHub directly, and a provider is offered only when its OAuth
 * app is configured, so a missing secret hides a button instead of breaking one.
 */
export interface IdentityProvider {
  /** The Auth.js provider id, which is also the last part of its callback URL. */
  id: "google" | "microsoft" | "github";
  name: string;
  /** The environment variable holding its OAuth client ID. */
  clientIdVariable: string;
}

export const IDENTITY_PROVIDERS: readonly IdentityProvider[] = [
  { id: "google", name: "Google", clientIdVariable: "AUTH_GOOGLE_ID" },
  { id: "microsoft", name: "Microsoft", clientIdVariable: "AUTH_MICROSOFT_ID" },
  { id: "github", name: "GitHub", clientIdVariable: "AUTH_GITHUB_ID" },
];

export type ProviderId = IdentityProvider["id"];

/** The providers whose OAuth client ID is set. */
export function configuredProviders(
  source: Record<string, string | undefined> = process.env,
): IdentityProvider[] {
  return IDENTITY_PROVIDERS.filter(
    (provider) => (source[provider.clientIdVariable] ?? "").trim() !== "",
  );
}

/** Accepts only a provider id we know: the form field is client input. */
export function providerFrom(value: unknown): ProviderId | null {
  return IDENTITY_PROVIDERS.find((provider) => provider.id === value)?.id ?? null;
}

/**
 * Fixed sign-ins for the tests and for local work, off unless ZEITWIDE_TEST_LOGIN=1 and the site
 * is on localhost. They exist only so tests can sign in without a real Google account.
 */
export const TEST_USERS = {
  member: {
    id: "test:member",
    name: "Grace Tester",
    email: "member@example.test",
    reviewer: false,
  },
  reviewer: {
    id: "test:reviewer",
    name: "Priya Tester",
    email: "reviewer@example.test",
    reviewer: true,
  },
} as const;

export type TestUser = keyof typeof TEST_USERS;

export function testLoginEnabled(
  source: Record<string, string | undefined> = process.env,
): boolean {
  if (source.ZEITWIDE_TEST_LOGIN !== "1") {
    return false;
  }
  let host: string;
  try {
    host = new URL(source.AUTH_URL ?? "http://localhost:3000").hostname;
  } catch {
    return false;
  }
  return host === "localhost" || host === "127.0.0.1";
}

export function testUserFrom(value: unknown): TestUser | null {
  return value === "member" || value === "reviewer" ? value : null;
}
