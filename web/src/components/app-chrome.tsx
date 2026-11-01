"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/cn";

import { Logo } from "./site-chrome";
import { Tip } from "./ui/tooltip";

const APP_LINKS = [
  { href: "/app", label: "Today" },
  { href: "/app/explorer", label: "Timetable" },
  { href: "/app/calculator", label: "Planner" },
  { href: "/app/changes", label: "Changes" },
  { href: "/app/time-travel", label: "Audit" },
  { href: "/app/developers", label: "Developers" },
] as const;

const ADMIN_LINKS = [
  { href: "/admin/review", label: "Review queue" },
  { href: "/admin/sources", label: "Source health" },
  { href: "/admin/releases", label: "Releases" },
] as const;

/** The signed-in header: dark, with the current page marked for screen readers too. */
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
    pathname === href || (href !== "/app" && pathname.startsWith(`${href}/`));
  return (
    <header className="bg-ink text-white">
      <div className="mx-auto flex min-h-16 max-w-[1440px] flex-wrap items-center gap-x-7 px-4 sm:px-8">
        <Link
          href="/"
          className="inline-flex min-h-11 items-center text-white no-underline hover:text-white"
        >
          <Logo inverted />
        </Link>
        <nav
          aria-label={pathname.startsWith("/admin") ? "Admin" : "App"}
          // Under a tablet's width the links drop to their own row and scroll sideways, so the
          // header stays two lines tall however many links there are.
          className="-mx-4 order-last flex basis-full overflow-x-auto px-4 sm:-mx-8 sm:px-8 md:order-none md:mx-0 md:basis-auto md:overflow-visible md:px-0"
        >
          {links.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              aria-current={current(link.href) ? "page" : undefined}
              className={cn(
                "inline-flex min-h-11 shrink-0 items-center px-3 text-[15px] whitespace-nowrap no-underline",
                current(link.href)
                  ? "font-semibold text-white shadow-[inset_0_-2px_0_#fff]"
                  : "text-[#a9afb8] hover:text-white",
              )}
            >
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm">
          {reviewer && (
            <Link
              href={pathname.startsWith("/admin") ? "/app/explorer" : "/admin/review"}
              className="inline-flex min-h-11 items-center text-[#a9afb8] hover:text-white"
            >
              {pathname.startsWith("/admin") ? "Back to the app" : "Admin"}
            </Link>
          )}
          <Tip label={name} side="bottom">
            <span
              aria-hidden="true"
              className="inline-flex size-9 items-center justify-center rounded-full bg-white text-[13px] font-bold text-ink"
            >
              {initials(name)}
            </span>
          </Tip>
          <span className="sr-only sm:not-sr-only sm:text-[#a9afb8]">{name}</span>
          <form action={signOut}>
            <Tip label="Sign out of Zeitwide" side="bottom">
              <button
                type="submit"
                className="inline-flex min-h-11 items-center rounded-md border border-[#343941] px-3 text-white hover:bg-[#2a2e35]"
              >
                Sign out
              </button>
            </Tip>
          </form>
        </div>
      </div>
    </header>
  );
}

/** Up to two capitals for the account badge: "Priya Tester" is "PT". */
export function initials(name: string): string {
  const parts = name.split(/[\s@.]+/).filter((part) => part !== "");
  return parts
    .slice(0, 2)
    .map((part) => part.slice(0, 1).toUpperCase())
    .join("");
}

export function PageHeading({ title, lead }: { title: string; lead?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <h1 className="text-4xl leading-11 font-bold tracking-[-0.02em]">{title}</h1>
      {lead !== undefined && <p className="max-w-3xl text-[15px] text-ink-muted">{lead}</p>}
    </div>
  );
}
