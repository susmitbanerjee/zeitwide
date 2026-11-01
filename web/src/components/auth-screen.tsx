import Link from "next/link";

import { startSignIn } from "@/auth/actions";
import { defaultLanding, safeCallback } from "@/auth/callback";
import { DIRECT_IDENTITY_PROVIDERS, type DirectIdentityProvider } from "@/auth/identity-providers";
import { signInProblem } from "@/auth/messages";
import { warmUpBackends } from "@/auth/warm-up";

import { Logo, SkipLink } from "./site-chrome";
import { ProviderButton } from "./provider-button";

type Mode = "signin" | "signup";

const COPY = {
  signin: {
    title: "Welcome back",
    lead: "Sign in to check rails, manage API keys and review changes.",
    defaultPath: defaultLanding("/signin"),
    switchLead: "New to Zeitwide?",
    switchLabel: "Create an account",
    switchPath: "/signup",
  },
  signup: {
    title: "Create your free account",
    lead: "Free during the public beta. Your first API key is a minute away.",
    defaultPath: defaultLanding("/signup"),
    switchLead: "Already have an account?",
    switchLabel: "Sign in",
    switchPath: "/signin",
  },
} as const;

const PROOF = [
  ["Two sources agree", "A change is published only when two independent official sources match."],
  ["A person signs off", "Every change is reviewed by a human before it reaches the dataset."],
  [
    "Receipts you can verify",
    "Each answer carries a signed receipt you can replay as of any date.",
  ],
] as const;

async function ProviderForm({ mode, searchParams }: { mode: Mode; searchParams: Props }) {
  const params = await searchParams;
  warmUpBackends();
  const copy = COPY[mode];
  const callbackUrl = safeCallback(params.callbackUrl, copy.defaultPath);
  const problem = signInProblem(params.error);
  const switchHref =
    params.callbackUrl === undefined
      ? copy.switchPath
      : `${copy.switchPath}?callbackUrl=${encodeURIComponent(callbackUrl)}`;
  return (
    <>
      <form action={startSignIn} className="flex flex-col gap-3">
        <input type="hidden" name="callbackUrl" value={callbackUrl} />
        {problem !== null && (
          <p role="alert" className="rounded-md bg-closed-soft px-3 py-2.5 text-sm text-closed-ink">
            {problem}
          </p>
        )}
        {DIRECT_IDENTITY_PROVIDERS.map(({ alias, name }) => (
          <ProviderButton
            key={alias}
            value={alias}
            tip={`${mode === "signup" ? "Create your account" : "Sign in"} with your ${name} account`}
          >
            <ProviderIcon provider={alias} />
            Continue with {name}
          </ProviderButton>
        ))}
        <div className="flex items-center gap-3 text-xs text-ink-subtle" aria-hidden="true">
          <span className="h-px flex-1 bg-line" />
          or
          <span className="h-px flex-1 bg-line" />
        </div>
        <ProviderButton
          value=""
          tip="Sign in with GitHub, or with your company's identity provider"
        >
          GitHub or company single sign-on
        </ProviderButton>
      </form>
      <p className="text-[15px] text-ink-muted">
        {copy.switchLead}{" "}
        <Link href={switchHref} prefetch={false} className="font-semibold">
          {copy.switchLabel}
        </Link>
      </p>
    </>
  );
}

type Props = PageProps<"/signin">["searchParams"];

/**
 * Sign-in and sign-up share one screen: Zeitwide has no passwords, so an identity provider's
 * first sign-in creates the account. Only the wording and the landing page differ.
 */
export function AuthScreen({ mode, searchParams }: { mode: Mode; searchParams: Props }) {
  const copy = COPY[mode];
  return (
    <>
      <SkipLink />
      <div className="grid min-h-dvh flex-1 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <aside className="hidden flex-col justify-between bg-ink p-12 text-white lg:flex">
          <Link href="/" className="w-fit text-white no-underline hover:text-white">
            <Logo inverted />
          </Link>
          <div className="flex max-w-md flex-col gap-8">
            <h2 className="text-4xl leading-tight font-bold tracking-[-0.02em]">
              Business days you can prove.
            </h2>
            <ul className="flex flex-col gap-5">
              {PROOF.map(([title, body]) => (
                <li key={title} className="flex gap-3">
                  <span
                    aria-hidden="true"
                    className="mt-1.5 h-4 w-1 shrink-0 rounded-full bg-closed"
                  />
                  <span>
                    <span className="block font-semibold">{title}</span>
                    <span className="block text-[15px] text-[#a9afb8]">{body}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <p className="font-mono text-xs text-[#8b94a3]">Fedwire · FedACH · FedNow · T2 · CHAPS</p>
        </aside>
        <main
          id="main"
          className="flex flex-col justify-center gap-8 px-4 py-10 sm:px-8 lg:items-center lg:px-16"
        >
          <Link href="/" className="w-fit text-ink no-underline hover:text-ink lg:hidden">
            <Logo />
          </Link>
          <div className="flex w-full max-w-sm flex-col gap-5 lg:max-w-md">
            <div className="flex flex-col gap-2">
              <h1 className="text-3xl leading-9 font-bold tracking-[-0.02em]">{copy.title}</h1>
              <p className="text-[15px] text-ink-muted">{copy.lead}</p>
            </div>
            <ProviderForm mode={mode} searchParams={searchParams} />
            <p className="text-[13px] leading-5 text-ink-subtle">
              Zeitwide never stores a password. Your provider shares your name and email, and that
              is all we keep.
            </p>
          </div>
        </main>
      </div>
    </>
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
