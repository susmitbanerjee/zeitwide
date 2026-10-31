import { getToken } from "next-auth/jwt";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";

import { auth, keycloakSignOutUrl, signOut } from "@/auth";
import { env } from "@/lib/env";

import { AppHeader } from "./app-chrome";
import { QueryProvider } from "./query-provider";
import { SkipLink } from "./site-chrome";

async function signOutAction(): Promise<void> {
  "use server";
  const token = await getToken({
    req: { headers: await headers() },
    secret: process.env.AUTH_SECRET ?? "",
    secureCookie: env.appUrl.startsWith("https://"),
  });
  await signOut({ redirect: false });
  // Ends the Keycloak session too, so the next sign-in asks who you are.
  redirect(keycloakSignOutUrl(token?.idToken, env.appUrl));
}

/**
 * The frame for the app and admin. It reads the session on the server: no session, or one whose
 * tokens could not be refreshed, goes back to sign-in. The API still checks every call itself.
 */
export async function SignedInShell({
  children,
  reviewerOnly = false,
  path,
}: {
  children: ReactNode;
  reviewerOnly?: boolean;
  path: string;
}) {
  const session = await auth();
  if (session === null || session.error !== undefined) {
    redirect(`/signin?callbackUrl=${encodeURIComponent(path)}`);
  }
  const reviewer = session.user.reviewer;
  return (
    <>
      <SkipLink />
      <AppHeader
        name={session.user.name ?? session.user.email ?? "Signed in"}
        reviewer={reviewer}
        signOut={signOutAction}
      />
      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6">
        {reviewerOnly && !reviewer ? (
          <div className="flex flex-col gap-2">
            <h1 className="text-3xl font-bold">Reviewers only</h1>
            <p className="text-ink-muted">
              The admin screens need the Keycloak role <span className="font-mono">reviewer</span>.
            </p>
          </div>
        ) : (
          <QueryProvider>{children}</QueryProvider>
        )}
      </main>
    </>
  );
}
