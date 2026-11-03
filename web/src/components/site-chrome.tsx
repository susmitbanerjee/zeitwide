import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";

import { DATASET_LICENSE, ORGANIZATION } from "@/lib/site-identity";

/** The Zeitband: seven days, one closed, two weekend. */
export function Logo({ inverted = false }: { inverted?: boolean }) {
  const day = inverted ? "#ffffff" : "#111317";
  const weekend = inverted ? "#8b94a3" : "#c9cdd3";
  const fills = [day, day, "#e5501e", day, day, weekend, weekend];
  return (
    <span className="inline-flex items-center gap-2.5">
      <svg width="25" height="18" viewBox="0 0 28 20" aria-hidden="true">
        {fills.map((fill, index) => (
          <rect key={index} x={index * 4} y="0" width="2.6" height="20" rx="1" fill={fill} />
        ))}
      </svg>
      <span className="text-lg font-bold tracking-[-0.02em]">Zeitwide</span>
    </span>
  );
}

export function SkipLink() {
  return (
    <a
      href="#main"
      className="sr-only rounded bg-surface px-3 py-2 font-semibold focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50"
    >
      Skip to content
    </a>
  );
}

const PUBLIC_LINKS = [
  { href: "/calendars", label: "Calendars", prefetch: true },
  { href: "/app/calculator", label: "Planner", prefetch: false },
  { href: "/docs", label: "Developers", prefetch: true },
  { href: "/docs/api", label: "API reference", prefetch: true },
] as const;

/** The public header. Static: it never reads the session, so answer pages stay cacheable. */
export function SiteHeader() {
  return (
    <header className="border-b border-line bg-surface">
      <div className="mx-auto flex min-h-16 max-w-[1376px] flex-wrap items-center gap-x-8 px-4 sm:px-8">
        <Link href="/" className="inline-flex min-h-11 items-center text-ink no-underline">
          <Logo />
        </Link>
        <nav
          aria-label="Main"
          // On a phone the links drop to their own row and scroll sideways instead of stacking.
          className="-mx-4 order-last flex basis-full items-center overflow-x-auto px-4 sm:-mx-8 sm:px-8 md:order-none md:mx-0 md:basis-auto md:overflow-visible md:px-0"
        >
          {PUBLIC_LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              prefetch={link.prefetch}
              className="inline-flex min-h-11 shrink-0 items-center px-3 text-[15px] whitespace-nowrap text-ink no-underline hover:text-accent"
            >
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-1 sm:gap-2">
          <Link
            href="/signin"
            prefetch={false}
            className="inline-flex min-h-11 items-center px-3.5 text-[15px] font-semibold text-ink no-underline hover:text-accent"
          >
            Sign in
          </Link>
          <Link
            href="/signup"
            prefetch={false}
            className="inline-flex min-h-11 items-center rounded-md bg-ink px-4 text-[15px] font-semibold text-white no-underline hover:bg-[#2a2e35] hover:text-white"
          >
            <span className="sm:hidden">Get API key</span>
            <span className="hidden sm:inline">Get a free API key</span>
          </Link>
        </div>
      </div>
    </header>
  );
}

/** Says who publishes the site, where its code lives and the data's license: what the JSON-LD
 * Organization states on every page. */
export function SiteFooter() {
  return (
    <footer className="mt-12 border-t border-line bg-surface">
      <div className="mx-auto flex max-w-[1376px] flex-wrap justify-between gap-x-6 gap-y-2 px-4 py-5 text-[13px] leading-5 text-ink-muted sm:px-8">
        <p>
          <Link href="/" className="inline-flex min-h-11 items-center gap-2 text-ink">
            <Image src={ORGANIZATION.logoPath} alt="" width={20} height={20} unoptimized />
            {ORGANIZATION.name}
          </Link>
        </p>
        {ORGANIZATION.profiles.map((profile) => (
          <p key={profile.href}>
            <a
              href={profile.href}
              rel="noopener noreferrer"
              className="inline-flex min-h-11 items-center"
            >
              {profile.text}
            </a>
          </p>
        ))}
        <p>
          <Link href="/pricing" className="inline-flex min-h-11 items-center">
            Free during the public beta, including commercial use of the API
          </Link>
        </p>
        <p>
          <Link href="/sources" className="inline-flex min-h-11 items-center">
            Every date comes from an official source
          </Link>
        </p>
        <p className="inline-flex min-h-11 items-center">
          Data: Zeitwide open dataset,&nbsp;
          <a href={DATASET_LICENSE.url} rel="license noopener noreferrer">
            {DATASET_LICENSE.name}
          </a>
          &nbsp;· Library: MIT
        </p>
      </div>
    </footer>
  );
}

export function PublicShell({ children }: { children: ReactNode }) {
  return (
    <>
      <SkipLink />
      <SiteHeader />
      <main
        id="main"
        className="mx-auto w-full max-w-[1376px] flex-1 px-4 py-6 sm:px-8 sm:pt-10 sm:pb-8"
      >
        {children}
      </main>
      <SiteFooter />
    </>
  );
}

export function Breadcrumbs({ items }: { items: { href?: string; label: string }[] }) {
  return (
    <nav aria-label="Breadcrumb" className="text-sm text-ink-muted">
      <ol className="flex flex-wrap items-center gap-1">
        {items.map((item, index) => (
          <li key={item.label} className="inline-flex items-center gap-1">
            {index > 0 && <span aria-hidden="true">/</span>}
            {item.href === undefined ? (
              <span aria-current="page">{item.label}</span>
            ) : (
              <Link href={item.href} className="inline-flex min-h-11 items-center">
                {item.label}
              </Link>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}
