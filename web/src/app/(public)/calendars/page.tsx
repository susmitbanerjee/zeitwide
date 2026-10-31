import type { Metadata } from "next";
import Link from "next/link";

import { Card, Table, Td, Th } from "@/components/ui/primitives";
import { groupCalendars } from "@/lib/answers";
import { getCalendars } from "@/lib/api-server";
import { env } from "@/lib/env";
import { formatDate } from "@/lib/format";
import { absoluteUrl } from "@/lib/seo";

export const metadata: Metadata = {
  title: "Calendars",
  description: "Every payment rail and public holiday calendar Zeitwide covers, with its sources.",
  alternates: { canonical: absoluteUrl(env.siteUrl, "/calendars") },
};

export default async function CalendarsPage() {
  const { calendars } = await getCalendars();
  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <h1 className="text-4xl font-bold tracking-[-0.02em]">Calendars</h1>
        <p className="text-ink-muted">
          Every calendar we cover. Dates outside a calendar&apos;s coverage are never guessed.
        </p>
      </header>
      {groupCalendars(calendars).map((group) => (
        <section key={group.label} aria-label={group.label} className="flex flex-col gap-3">
          <h2 className="text-xl font-semibold">{group.label}</h2>
          <Card>
            <Table>
              <thead>
                <tr>
                  <Th scope="col">Calendar</Th>
                  <Th scope="col">Time zone</Th>
                  <Th scope="col">Weekend</Th>
                  <Th scope="col">Covered</Th>
                </tr>
              </thead>
              <tbody>
                {group.calendars.map((calendar) => (
                  <tr key={calendar.id}>
                    <Td>
                      <Link href={`/calendars/${calendar.id}`} className="font-semibold">
                        {calendar.name}
                      </Link>{" "}
                      <span className="font-mono text-xs text-ink-subtle">{calendar.id}</span>
                    </Td>
                    <Td className="font-mono text-[13px]">{calendar.timezone}</Td>
                    <Td>
                      {calendar.weekend.length === 0
                        ? "None, open every day"
                        : calendar.weekend.join(", ")}
                    </Td>
                    <Td>
                      {calendar.coverage.type === "unlimited"
                        ? "Every date"
                        : calendar.coverage.type === "none"
                          ? "Nothing yet"
                          : `${formatDate(calendar.coverage.from ?? "", "plain")} to ${formatDate(calendar.coverage.to ?? "", "plain")}`}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </Card>
        </section>
      ))}
    </div>
  );
}
