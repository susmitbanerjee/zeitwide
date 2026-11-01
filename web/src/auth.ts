import NextAuth, { type NextAuthConfig } from "next-auth";
import Credentials from "next-auth/providers/credentials";
import GitHub from "next-auth/providers/github";
import Google from "next-auth/providers/google";
import type { OAuthConfig } from "next-auth/providers";

import {
  configuredProviders,
  TEST_USERS,
  testLoginEnabled,
  testUserFrom,
} from "./auth/identity-providers";
import { REVIEWER_ROLE, reviewerEmails, rolesFor } from "./auth/roles";

/**
 * Microsoft sign-in for work, school and personal accounts at once. It is a plain OAuth provider
 * rather than Auth.js's OIDC one, and it asks for no `openid` scope on purpose: with it Microsoft
 * returns an ID token, and Auth.js checks that token's issuer, which differs for every tenant and
 * so never matches the shared "common" endpoint (the sign-in failed with an issuer mismatch).
 * Without it there is no ID token, and the profile comes from Microsoft Graph over the access
 * token instead.
 */
function microsoft(): OAuthConfig<{
  id: string;
  displayName?: string;
  mail?: string | null;
  userPrincipalName?: string;
}> {
  // The tests point these at a fake Microsoft on localhost; ignored anywhere else.
  const test = process.env.AUTH_MICROSOFT_TEST_BASE;
  const base = testLoginEnabled() && test !== undefined && test !== "" ? test : undefined;
  return {
    id: "microsoft",
    name: "Microsoft",
    type: "oauth",
    authorization: {
      url: base ?? "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
      // Always show the account chooser, even when one account is already signed in.
      params: { scope: "User.Read", prompt: "select_account" },
    },
    token:
      base === undefined
        ? "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        : `${base}/token`,
    userinfo: base === undefined ? "https://graph.microsoft.com/v1.0/me" : `${base}/me`,
    clientId: process.env.AUTH_MICROSOFT_ID ?? "",
    clientSecret: process.env.AUTH_MICROSOFT_SECRET ?? "",
    checks: ["pkce", "state"],
    profile: (profile) => {
      const email = profile.mail ?? profile.userPrincipalName ?? null;
      return { id: profile.id, name: profile.displayName ?? email, email, image: null };
    },
  };
}

function providers(): NextAuthConfig["providers"] {
  const list: NextAuthConfig["providers"] = [];
  for (const { id } of configuredProviders()) {
    if (id === "google") {
      list.push(
        Google({
          checks: ["pkce", "state"],
          // Always show the account chooser, even when one account is already signed in.
          authorization: { params: { prompt: "select_account" } },
        }),
      );
    } else if (id === "github") {
      list.push(GitHub({ checks: ["pkce", "state"] }));
    } else {
      list.push(microsoft());
    }
  }
  if (testLoginEnabled()) {
    list.push(
      Credentials({
        id: "test-login",
        name: "Test sign-in",
        credentials: { user: {} },
        authorize: (credentials) => {
          const user = testUserFrom(credentials.user);
          return user === null ? null : TEST_USERS[user];
        },
      }),
    );
  }
  return list;
}

const reviewers = reviewerEmails(process.env.ZEITWIDE_REVIEWER_EMAILS);

/**
 * Sign-in with Auth.js, straight to Google, Microsoft or GitHub (authorization code flow with
 * PKCE and state). The session is a JWT encrypted with AUTH_SECRET in an httpOnly, SameSite=Lax
 * cookie (Secure over HTTPS) holding who the person is and their roles. Calls to the API go
 * through /api/zw, which signs a short-lived token from it; the browser never holds one.
 */
export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: providers(),
  session: { strategy: "jwt", maxAge: 8 * 60 * 60 },
  pages: { signIn: "/signin", error: "/signin" },
  trustHost: true,
  callbacks: {
    jwt({ token, account, user, profile }) {
      // Only at sign-in does Auth.js hand over the provider's account; keep who they are after.
      if (account && user) {
        token.subject = `${account.provider}:${account.providerAccountId}`;
        token.roles =
          account.provider === "test-login"
            ? user.id === TEST_USERS.reviewer.id
              ? [REVIEWER_ROLE]
              : []
            : rolesFor(
                account.provider,
                user.email,
                // Google says so in the profile; no other provider is trusted for the role.
                (profile as { email_verified?: boolean } | undefined)?.email_verified === true,
                reviewers,
              );
      }
      return token;
    },
    session({ session, token }) {
      // Only what the screens need.
      const roles = token.roles ?? [];
      return {
        ...session,
        user: { ...session.user, roles, reviewer: roles.includes(REVIEWER_ROLE) },
      };
    },
  },
});
