import type { Metadata } from "next";

import { signIn } from "@/auth";
import {
  DIRECT_IDENTITY_PROVIDERS,
  type DirectIdentityProvider,
  identityProviderHint,
} from "@/auth/identity-providers";
import { Logo, SkipLink } from "@/components/site-chrome";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/primitives";

export const metadata: Metadata = { title: "Sign in", robots: { index: false, follow: false } };

// Rendered per request so its scripts carry the CSP nonce (see the app layout).
export const instant = false;

/** Only same-site paths, so the sign-in page can't be used to redirect anywhere else. */
function safeCallback(value: string | string[] | undefined): string {
  const path = Array.isArray(value) ? value[0] : value;
  return path !== undefined && path.startsWith("/") && !path.startsWith("//") ? path : "/app";
}

async function SignInForm({ searchParams }: PageProps<"/signin">) {
  const params = await searchParams;
  const callbackUrl = safeCallback(params.callbackUrl);
  const failed = params.error !== undefined;
  return (
    <form
      action={async (form: FormData) => {
        "use server";
        await signIn(
          "keycloak",
          { redirectTo: callbackUrl },
          identityProviderHint(form.get("provider")),
        );
      }}
      className="flex flex-col gap-3"
    >
      {failed && (
        <p role="alert" className="rounded-md bg-closed-soft px-3 py-2 text-sm text-closed">
          Sign-in didn&apos;t complete. Please try again.
        </p>
      )}
      {DIRECT_IDENTITY_PROVIDERS.map(({ alias, name }) => (
        <Button key={alias} type="submit" name="provider" value={alias} variant="outline">
          <ProviderIcon provider={alias} />
          Continue with {name}
        </Button>
      ))}
      <Button type="submit" variant="ghost">
        GitHub or company single sign-on
      </Button>
    </form>
  );
}

function ProviderIcon({ provider }: { provider: DirectIdentityProvider }) {
  if (provider === "microsoft") {
    return (
      <svg aria-hidden="true" viewBox="0 0 21 21" className="size-5">
        <path fill="#f25022" d="M1 1h9v9H1z" />
        <path fill="#7fba00" d="M11 1h9v9h-9z" />
        <path fill="#00a4ef" d="M1 11h9v9H1z" />
        <path fill="#ffb900" d="M11 11h9v9h-9z" />
      </svg>
    );
  }
  return (
    <svg aria-hidden="true" viewBox="0 0 48 48" className="size-5">
      <path
        fill="#FFC107"
        d="M43.6 20.1H42V20H24v8h11.3c-1.6 4.7-6.1 8-11.3 8-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 8 3l5.7-5.7C34 6.1 29.3 4 24 4 13 4 4 13 4 24s9 20 20 20 20-9 20-20c0-1.3-.1-2.7-.4-3.9z"
      />
      <path
        fill="#FF3D00"
        d="m6.3 14.7 6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 8 3l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"
      />
      <path
        fill="#4CAF50"
        d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2A11.9 11.9 0 0 1 24 36c-5.2 0-9.6-3.3-11.3-7.9l-6.5 5C9.5 39.6 16.2 44 24 44z"
      />
      <path
        fill="#1976D2"
        d="M43.6 20.1H42V20H24v8h11.3a12 12 0 0 1-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.7-.4-3.9z"
      />
    </svg>
  );
}

export default function SignInPage(props: PageProps<"/signin">) {
  return (
    <>
      <SkipLink />
      <main
        id="main"
        className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-6 px-4 py-16"
      >
        <Logo />
        <Card className="flex flex-col gap-4 p-6">
          <h1 className="text-2xl font-bold">Sign in to Zeitwide</h1>
          <p className="text-ink-muted">
            Use Google, Microsoft, GitHub or your company&apos;s single sign-on. Zeitwide never
            stores a password.
          </p>
          <SignInForm {...props} />
        </Card>
      </main>
    </>
  );
}
