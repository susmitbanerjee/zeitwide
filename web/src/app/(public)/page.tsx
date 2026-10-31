import type { Metadata } from "next";
import Link from "next/link";

import { DateFinder } from "@/components/date-finder";
import { Card, SectionTitle } from "@/components/ui/primitives";
import { groupCalendars } from "@/lib/answers";
import { getCalendars, getDays } from "@/lib/api-server";
import { env } from "@/lib/env";
import { formatDate } from "@/lib/format";
import { answerPath } from "@/lib/seo";

export const metadata: Metadata = { alternates: { canonical: env.siteUrl } };

export default async function HomePage() {
  const { calendars, dataset_release: release } = await getCalendars();
  const first = calendars[0];
  // "Today" is the API's: the first calendar's date now, in its own time zone.
  const today = first === undefined ? undefined : (await getDays(first.id, first.revision))?.today;
  return (
    <div className="flex flex-col gap-12">
      <section className="flex flex-col gap-5 pt-6">
        <h1 className="max-w-3xl text-5xl leading-[1.05] font-bold tracking-[-0.035em] sm:text-6xl">
          Business days you can prove.
        </h1>
        <p className="max-w-2xl text-lg leading-relaxed text-ink-muted">
          Zeitwide tells people, software and AI agents whether a payment rail is open on a date.
          Every answer comes with the official source and a warning before anything changes.
        </p>
      </section>

      <Card className="flex flex-col gap-4 p-6">
        <SectionTitle>Check a date</SectionTitle>
        <DateFinder calendars={calendars} initialDate={today ?? ""} />
      </Card>

      <section aria-labelledby="calendars" className="flex flex-col gap-6">
        <SectionTitle id="calendars">Calendars</SectionTitle>
        {groupCalendars(calendars).map((group) => (
          <div key={group.label} className="flex flex-col gap-3">
            <h3 className="text-sm font-semibold tracking-[0.08em] text-ink-subtle uppercase">
              {group.label}
            </h3>
            <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {group.calendars.map((calendar) => (
                <li key={calendar.id}>
                  <Card className="flex h-full flex-col gap-1 p-4">
                    <Link href={`/calendars/${calendar.id}`} className="text-lg font-semibold">
                      {calendar.name}
                    </Link>
                    <span className="font-mono text-xs text-ink-subtle">{calendar.id}</span>
                    {today !== undefined && (
                      <Link href={answerPath(calendar.id, today)} className="mt-2 text-sm">
                        Is {formatDate(today, "short")} a business day?
                      </Link>
                    )}
                  </Card>
                </li>
              ))}
            </ul>
          </div>
        ))}
        <p className="text-sm text-ink-muted">
          Dataset release <span className="font-mono">{release}</span>.
        </p>
      </section>
    </div>
  );
}
