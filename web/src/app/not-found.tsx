import Link from "next/link";

import { PublicShell } from "@/components/site-chrome";
import { buttonVariants } from "@/components/ui/button";
import { Eyebrow } from "@/components/ui/primitives";

export default function NotFound() {
  return (
    <PublicShell>
      <div className="flex max-w-xl flex-col items-start gap-4">
        <Eyebrow>404</Eyebrow>
        <h1 className="text-4xl leading-11 font-bold tracking-[-0.02em]">
          That page isn&apos;t on the calendar.
        </h1>
        <p className="text-ink-muted">
          The link may be out of date, or the calendar or date in it isn&apos;t covered. Try one of
          these instead.
        </p>
        <div className="flex flex-wrap gap-3">
          <Link href="/calendars" className={buttonVariants()}>
            Browse calendars
          </Link>
          <Link href="/docs" className={buttonVariants({ variant: "outline" })}>
            API docs
          </Link>
          <Link href="/signin" prefetch={false} className={buttonVariants({ variant: "ghost" })}>
            Sign in
          </Link>
        </div>
      </div>
    </PublicShell>
  );
}
