import { redirect } from "next/navigation";
import type { ReactNode } from "react";

import { auth, signOut } from "@/auth";

import { AppHeader } from "./app-chrome";
import { QueryProvider } from "./query-provider";
import { SkipLink } from "./site-chrome";

async function signOutAction(): Promise<void> {
  "use server";
  await signOut({ redirectTo: "/" });
}

/**
 * The frame for the app and admin. It reads the session on the server: no session goes back to
 * sign-in. The API still checks every call itself.
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
  if (session === null) {
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
      <main id="main" className="mx-auto w-full max-w-[1440px] flex-1 px-4 py-7 sm:px-8">
        {reviewerOnly && !reviewer ? (
          <div className="flex flex-col gap-2">
            <h1 className="text-3xl font-bold">Reviewers only</h1>
            <p className="text-ink-muted">
              The admin screens are for reviewers. Ask an owner to add your email to the reviewer
              list.
            </p>
          </div>
        ) : (
          <QueryProvider>{children}</QueryProvider>
        )}
      </main>
    </>
  );
}
