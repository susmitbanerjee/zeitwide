/**
 * Keycloak identity providers (aliases from deploy/keycloak/realm.json) that the sign-in page
 * offers as direct buttons. Others, such as GitHub and company SSO, stay on Keycloak's own page.
 */
export const DIRECT_IDENTITY_PROVIDERS = [
  { alias: "google", name: "Google" },
  { alias: "microsoft", name: "Microsoft" },
] as const;

export type DirectIdentityProvider = (typeof DIRECT_IDENTITY_PROVIDERS)[number]["alias"];

/**
 * Keycloak's `kc_idp_hint` skips its login page and goes straight to that provider. Only listed
 * aliases pass; anything else gets no hint, so the visitor lands on Keycloak's page instead.
 */
export function identityProviderHint(
  value: unknown,
): Partial<Record<"kc_idp_hint", DirectIdentityProvider>> {
  const provider = DIRECT_IDENTITY_PROVIDERS.find(({ alias }) => alias === value);
  return provider === undefined ? {} : { kc_idp_hint: provider.alias };
}
