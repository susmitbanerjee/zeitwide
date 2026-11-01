import NextAuth, { customFetch } from "next-auth";
import Keycloak from "next-auth/providers/keycloak";

import {
  endSessionUrl,
  needsRefresh,
  refreshTokens,
  REVIEWER_ROLE,
  rolesFromAccessToken,
} from "./auth/tokens";

const issuer = process.env.AUTH_KEYCLOAK_ISSUER ?? "http://localhost:8180/realms/zeitwide";
/** Longer than a cold Keycloak needs to start (about 30 s), shorter than the 5-minute request limit. */
const KEYCLOAK_COLD_START_MS = 60_000;
const clientId = process.env.AUTH_KEYCLOAK_ID ?? "zeitwide-web";

/**
 * Keycloak sign-in with Auth.js: authorization code flow with PKCE, state and nonce, for the
 * public zeitwide-web client. The session is a JWT encrypted with AUTH_SECRET in an httpOnly,
 * SameSite=Lax cookie (Secure over HTTPS). Access and refresh tokens stay in that cookie and
 * reach the API only through /api/zw; the browser's session object carries neither.
 */
export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      issuer,
      clientId,
      client: { token_endpoint_auth_method: "none" },
      // Keycloak scales to zero on Cloud Run and takes about 30 seconds to start, so its first
      // answer after a quiet spell can't be cut off after Auth.js's default of a few seconds.
      [customFetch]: (input: RequestInfo | URL, init?: RequestInit) =>
        fetch(input, { ...init, signal: AbortSignal.timeout(KEYCLOAK_COLD_START_MS) }),
      checks: ["pkce", "state", "nonce"],
    }),
  ],
  session: { strategy: "jwt", maxAge: 8 * 60 * 60 },
  pages: { signIn: "/signin", error: "/signin" },
  trustHost: true,
  callbacks: {
    async jwt({ token, account }) {
      if (account?.access_token !== undefined) {
        return {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token,
          // Only for Keycloak's end-session call on sign-out (id_token_hint).
          idToken: account.id_token,
          expiresAt: account.expires_at,
          roles: rolesFromAccessToken(account.access_token),
          error: undefined,
        };
      }
      if (!needsRefresh(token.expiresAt, Date.now())) {
        return token;
      }
      if (token.refreshToken === undefined) {
        return { ...token, error: "RefreshTokenError" as const };
      }
      try {
        const refreshed = await refreshTokens(issuer, clientId, token.refreshToken, Date.now());
        return {
          ...token,
          ...refreshed,
          roles: rolesFromAccessToken(refreshed.accessToken),
          error: undefined,
        };
      } catch {
        return { ...token, error: "RefreshTokenError" as const };
      }
    },
    session({ session, token }) {
      // Only what the UI needs; tokens never go to the browser.
      const roles = token.roles ?? [];
      return {
        ...session,
        user: {
          ...session.user,
          roles,
          reviewer: roles.includes(REVIEWER_ROLE),
        },
        ...(token.error === undefined ? {} : { error: token.error }),
      };
    },
  },
});

/** Where Sign out sends the browser: Keycloak's end-session page, which then returns home. */
export function keycloakSignOutUrl(idToken: string | undefined, appOrigin: string): string {
  return endSessionUrl(issuer, clientId, idToken, `${appOrigin}/`);
}
