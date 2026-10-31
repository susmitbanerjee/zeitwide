"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/cn";

import { Logo } from "./site-chrome";

const APP_LINKS = [
  { href: "/app/explorer", label: "Explorer" },
  { href: "/app/calculator", label: "Calculator" },
  { href: "/app/time-travel", label: "Time travel" },
  { href: "/app/changes", label: "Changes" },
  { href: "/app/developers", label: "Keys and webhooks" },
  { href: "/app/developers/mcp", label: "MCP" },
] as const;

const ADMIN_LINKS = [
  { href: "/admin/review", label: "Review queue" },
  { href: "/admin/sources", label: "Source health" },
  { href: "/admin/releases", label: "Releases" },
] as const;

/** The signed-in header: navy, with the current page marked for screen readers too. */
export function AppHeader({
  name,
  reviewer,
  signOut,
}: {
  name: string;
  reviewer: boolean;
  signOut: () => Promise<void>;
}) {
  const pathname = usePathname();
  const links = pathname.startsWith("/admin") ? ADMIN_LINKS : APP_LINKS;
  const current = (href: string) =>
    pathname === href || (href !== "/app/developers" && pathname.startsWith(`${href}/`));
  return (
    <header className="bg-ink text-white">
      <div className="mx-auto flex min-h-16 max-w-7xl flex-wrap items-center gap-x-6 gap-y-1 px-4 sm:px-6">
        <Link
          href="/"
          className="inline-flex min-h-11 items-center text-white no-underline hover:text-white"
        >
          <Logo inverted />
        </Link>
        <nav
          aria-label={pathname.startsWith("/admin") ? "Admin" : "App"}
          className="flex flex-wrap"
        >
          {links.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              aria-current={current(link.href) ? "page" : undefined}
              className={cn(
                "inline-flex min-h-11 items-center px-3 text-[15px] no-underline",
                current(link.href)
                  ? "font-semibold text-white shadow-[inset_0_-2px_0_#fff]"
                  : "text-[#c9cfd8] hover:text-white",
              )}
            >
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex flex-wrap items-center gap-3 text-sm">
          {reviewer && (
            <Link
              href={pathname.startsWith("/admin") ? "/app/explorer" : "/admin/review"}
              className="inline-flex min-h-11 items-center text-[#c9cfd8] hover:text-white"
            >
              {pathname.startsWith("/admin") ? "Back to the app" : "Admin"}
            </Link>
          )}
          <span className="text-[#c9cfd8]">{name}</span>
          <form action={signOut}>
            <button
              type="submit"
              className="inline-flex min-h-11 items-center rounded-md border border-[#4a5463] px-3 text-white hover:bg-[#1d2a40]"
            >
              Sign out
            </button>
          </form>
        </div>
      </div>
    </header>
  );
}

export function PageHeading({ title, lead }: { title: string; lead?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <h1 className="text-3xl font-bold tracking-[-0.02em] sm:text-4xl">{title}</h1>
      {lead !== undefined && <p className="max-w-3xl text-ink-muted">{lead}</p>}
    </div>
  );
}
