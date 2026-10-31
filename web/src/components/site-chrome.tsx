import Link from "next/link";
import type { ReactNode } from "react";

/** The Zeitband: seven days, one closed, two weekend. */
export function Logo({ inverted = false }: { inverted?: boolean }) {
  const day = inverted ? "#ffffff" : "#0e1726";
  const weekend = inverted ? "#8b94a3" : "#c3c9d2";
  const fills = [day, day, "#b54708", day, day, weekend, weekend];
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
  { href: "/calendars", label: "Calendars" },
  { href: "/docs", label: "Developers" },
  { href: "/docs/api", label: "API reference" },
] as const;

/** The public header. Static: it never reads the session, so answer pages stay cacheable. */
export function SiteHeader() {
  return (
    <header className="border-b border-line bg-surface">
      <div className="mx-auto flex min-h-16 max-w-6xl flex-wrap items-center gap-x-6 gap-y-1 px-4 sm:px-6">
        <Link href="/" className="inline-flex min-h-11 items-center text-ink no-underline">
          <Logo />
        </Link>
        <nav aria-label="Main" className="flex flex-wrap items-center">
          {PUBLIC_LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className="inline-flex min-h-11 items-center px-3 text-[15px] text-ink no-underline hover:text-accent"
            >
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-2">
          <Link
            href="/app"
            prefetch={false}
            className="inline-flex min-h-11 items-center rounded-md bg-ink px-4 text-[15px] font-semibold text-white no-underline hover:bg-[#1d2a40] hover:text-white"
          >
            Open the app
          </Link>
        </div>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="mt-16 border-t border-line bg-surface">
      <div className="mx-auto flex max-w-6xl flex-wrap justify-between gap-4 px-4 py-6 text-sm text-ink-muted sm:px-6">
        <p>Free during the public beta, including commercial use of the API.</p>
        <p>Data: Zeitwide open dataset, CC BY-NC 4.0 · Library: MIT</p>
      </div>
    </footer>
  );
}

export function PublicShell({ children }: { children: ReactNode }) {
  return (
    <>
      <SkipLink />
      <SiteHeader />
      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">
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
