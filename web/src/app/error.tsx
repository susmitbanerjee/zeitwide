"use client";

import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { Eyebrow } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";

/** The last resort for an unhandled error: no details to the visitor, a way to retry or leave. */
export default function ErrorPage({ reset }: { error: Error; reset: () => void }) {
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-xl flex-1 flex-col justify-center gap-4 px-4 py-16"
    >
      <Eyebrow>Something went wrong</Eyebrow>
      <h1 className="text-4xl leading-11 font-bold tracking-[-0.02em]">
        We couldn&apos;t load this page.
      </h1>
      <p className="text-ink-muted">
        It&apos;s on our side, and nothing you did. Try again, or come back in a minute.
      </p>
      <div className="flex flex-wrap gap-3">
        <button type="button" onClick={reset} className={cn(buttonVariants())}>
          Try again
        </button>
        <Link href="/" className={buttonVariants({ variant: "outline" })}>
          Go to the home page
        </Link>
      </div>
    </main>
  );
}
