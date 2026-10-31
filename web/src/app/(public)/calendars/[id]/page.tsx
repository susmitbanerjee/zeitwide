import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { DayStatus } from "@/components/day-status";
import { Breadcrumbs } from "@/components/site-chrome";
import { Card, Table, Td, Th } from "@/components/ui/primitives";
import { closures, dayKind, findCalendar } from "@/lib/answers";
import { getCalendars, getDays } from "@/lib/api-server";
import { env } from "@/lib/env";
import { formatDate } from "@/lib/format";
import { absoluteUrl, answerPath } from "@/lib/seo";

export const ensureStatic = "navigation";

export async function generateStaticParams(): Promise<{ id: string }[]> {
  const { calendars } = await getCalendars();
  return calendars.map((calendar) => ({ id: calendar.id }));
}

async function load(id: string) {
  const list = await getCalendars();
  const calendar = findCalendar(list, id);
  if (calendar === undefined) {
    return null;
  }
  const range = await getDays(calendar.id, calendar.revision);
  return range === null ? null : { calendar, range };
}

export async function generateMetadata({
  params,
}: PageProps<"/calendars/[id]">): Promise<Metadata> {
  const data = await load((await params).id);
  if (data === null) {
    return { title: "Not found", robots: { index: false } };
  }
  return {
    title: `${data.calendar.name} closures and business days`,
    description: `When ${data.calendar.name} is closed, from ${formatDate(data.range.from, "plain")} to ${formatDate(data.range.to, "plain")}, with the official source for each date.`,
    alternates: { canonical: absoluteUrl(env.siteUrl, `/calendars/${data.calendar.id}`) },
  };
}

export default async function CalendarPage({ params }: PageProps<"/calendars/[id]">) {
  const data = await load((await params).id);
  if (data === null) {
    notFound();
  }
  const { calendar, range } = data;
  const closed = closures(range);
  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Breadcrumbs
          items={[{ href: "/calendars", label: "Calendars" }, { label: calendar.name }]}
        />
        <h1 className="text-4xl font-bold tracking-[-0.02em]">{calendar.name} closures</h1>
        <p className="text-ink-muted">
          From {formatDate(range.from, "plain")} to {formatDate(range.to, "plain")}.{" "}
          {calendar.weekend.length === 0
            ? `${calendar.name} runs every day of the year.`
            : `${calendar.name} is also closed every ${calendar.weekend.join(" and ")}.`}
        </p>
        <p>
          <Link href={answerPath(calendar.id, range.today)}>
            Is today, {formatDate(range.today, "long")}, a {calendar.name} business day?
          </Link>
        </p>
      </header>
      <Card>
        <Table>
          <caption className="sr-only">{calendar.name} closures</caption>
          <thead>
            <tr>
              <Th scope="col">Date</Th>
              <Th scope="col">Status</Th>
              <Th scope="col">Reason</Th>
              <Th scope="col">Next business day</Th>
            </tr>
          </thead>
          <tbody>
            {closed.length === 0 ? (
              <tr>
                <Td colSpan={4} className="text-ink-muted">
                  No closures in this period.
                </Td>
              </tr>
            ) : (
              closed.map((day) => (
                <tr key={day.date}>
                  <Td>
                    <Link href={answerPath(calendar.id, day.date)} className="font-mono">
                      {formatDate(day.date)}
                    </Link>
                  </Td>
                  <Td>
                    <DayStatus kind={dayKind(day, calendar.weekend)} />
                  </Td>
                  <Td>{day.reason}</Td>
                  <Td className="font-mono">
                    {day.next_business_day === undefined ? "—" : formatDate(day.next_business_day)}
                  </Td>
                </tr>
              ))
            )}
          </tbody>
        </Table>
      </Card>
    </div>
  );
}
