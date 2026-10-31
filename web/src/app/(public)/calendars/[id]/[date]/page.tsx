import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";

import { CopyButton } from "@/components/copy-button";
import { DayStatus } from "@/components/day-status";
import { DayStrip } from "@/components/day-strip";
import { JsonLd } from "@/components/json-ld";
import { Breadcrumbs } from "@/components/site-chrome";
import { buttonVariants } from "@/components/ui/button";
import { Card, Code, SectionTitle, Table, Td, Th } from "@/components/ui/primitives";
import { answerPageData } from "@/lib/answer-data";
import { answerPageParams, dayKind, daysAround, nextClosure } from "@/lib/answers";
import { getCalendars, getDays } from "@/lib/api-server";
import { env } from "@/lib/env";
import { formatDate, formatInstant, plural } from "@/lib/format";
import {
  absoluteUrl,
  answerDescription,
  answerJsonLd,
  answerPath,
  answerQuestion,
} from "@/lib/seo";

// Every answer page is complete static HTML: listed dates at build, others on first request.
export const ensureStatic = "navigation";

/** Each calendar's current year and the next 90 days, as the API's default window lists them. */
export async function generateStaticParams(): Promise<{ id: string; date: string }[]> {
  const { calendars } = await getCalendars();
  const ranges = await Promise.all(
    calendars.map(async (calendar) => ({
      calendarId: calendar.id,
      range: await getDays(calendar.id, calendar.revision),
    })),
  );
  return answerPageParams(ranges);
}

export async function generateMetadata({
  params,
}: PageProps<"/calendars/[id]/[date]">): Promise<Metadata> {
  const { id, date } = await params;
  const data = await answerPageData(id, date);
  if (data === null) {
    return { title: "Not found", robots: { index: false } };
  }
  const title = answerQuestion(data.calendar, data.day.date);
  const description = answerDescription(data.calendar, data.day);
  const canonical = absoluteUrl(env.siteUrl, answerPath(data.calendar.id, data.day.date));
  return {
    title: { absolute: title },
    description,
    alternates: { canonical },
    openGraph: { title, description, url: canonical, type: "article" },
  };
}

export default async function AnswerPage({ params }: PageProps<"/calendars/[id]/[date]">) {
  const { id, date } = await params;
  const data = await answerPageData(id, date);
  if (data === null) {
    notFound();
  }
  const { calendar, day, window, others, datasetRelease } = data;
  const kind = dayKind(day, calendar.weekend);
  const around = window === null ? [] : daysAround(window, day.date);
  const upcoming = window === null ? undefined : nextClosure(window, day.date);
  const lastRead = day.sources
    .map((source) => source.retrieved_at)
    .sort()
    .at(-1);
  const citation = [
    `Zeitwide. ${calendar.id}, ${day.date}: ${day.is_open ? "open" : "closed"}`,
    day.reason === undefined ? "" : `, ${day.reason}`,
    day.sources.length > 0 ? `. Sources: ${day.sources.map((s) => s.publisher).join("; ")}` : "",
    `. Release ${datasetRelease}. ${absoluteUrl(env.siteUrl, answerPath(calendar.id, day.date))}`,
  ].join("");

  return (
    <article className="flex flex-col gap-10">
      <JsonLd data={answerJsonLd(env.siteUrl, calendar, day)} />
      <header className="flex flex-col gap-3">
        <Breadcrumbs
          items={[
            { href: "/calendars", label: "Calendars" },
            { href: `/calendars/${calendar.id}`, label: calendar.name },
            { label: formatDate(day.date, "plain") },
          ]}
        />
        <h1 className="max-w-4xl text-3xl leading-tight font-bold tracking-[-0.02em] sm:text-4xl">
          {answerQuestion(calendar, day.date)}
        </h1>
      </header>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
        <Card className="overflow-hidden">
          <section aria-labelledby="answer" className="flex flex-col gap-3 p-6">
            <p
              id="answer"
              className={`text-6xl leading-none font-bold tracking-[-0.04em] ${day.is_open ? "text-ink" : "text-closed"}`}
            >
              {day.is_open ? "Yes." : "No."}
            </p>
            <p className="text-xl leading-snug font-semibold">
              {day.is_open
                ? `${calendar.name} is open for settlement.`
                : day.decided_by === "weekend_rule"
                  ? `${calendar.name} does not settle at weekends.`
                  : `${calendar.name} is closed: ${day.reason ?? "a closure"}.`}
            </p>
            <div className="flex flex-wrap gap-2">
              <DayStatus kind={kind} />
              {day.decided_by === "entry" && (
                <span className="inline-flex items-center rounded border border-line-strong px-2 py-0.5 text-[13px] font-semibold">
                  {day.basis === "projected" ? "Projected" : "Published"} ·{" "}
                  {plural(day.sources.length, "source")}
                </span>
              )}
            </div>
          </section>
          <dl className="grid border-t border-line sm:grid-cols-2">
            <div className="flex flex-col gap-1 border-line p-5 sm:border-r">
              <dt className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">
                Next business day
              </dt>
              <dd className="font-mono text-lg">
                {day.next_business_day === undefined ? (
                  "Beyond our sources"
                ) : (
                  <Link href={answerPath(calendar.id, day.next_business_day)}>
                    {formatDate(day.next_business_day)}
                  </Link>
                )}
              </dd>
            </div>
            <div className="flex flex-col gap-1 p-5">
              <dt className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">
                Previous business day
              </dt>
              <dd className="font-mono text-lg">
                {day.previous_business_day === undefined ? (
                  "Before our sources"
                ) : (
                  <Link href={answerPath(calendar.id, day.previous_business_day)}>
                    {formatDate(day.previous_business_day)}
                  </Link>
                )}
              </dd>
            </div>
          </dl>
          <div className="flex flex-col gap-3 border-t border-line bg-surface-sunken p-5 text-sm">
            {day.sources.length > 0 ? (
              <ul className="flex flex-col gap-1">
                {day.sources.map((source) => (
                  <li key={source.snapshot_sha256}>
                    <span className="text-ink-muted">Source: </span>
                    <a href={source.url} rel="noopener noreferrer" target="_blank">
                      {source.publisher}
                    </a>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-ink-muted">
                Decided by the weekend rule:{" "}
                {calendar.weekend.length === 0 ? "no weekend days" : calendar.weekend.join(" and ")}{" "}
                are closed on {calendar.name}.
              </p>
            )}
            <p className="text-ink-muted">
              {lastRead === undefined ? "" : `Checked ${formatInstant(lastRead)} · `}release{" "}
              <span className="font-mono">{datasetRelease}</span>
            </p>
            <div className="flex flex-wrap gap-2">
              <Link
                href={`/app/evidence/${calendar.id}/${day.date}`}
                prefetch={false}
                className={buttonVariants({ variant: "primary" })}
              >
                How we know
              </Link>
              <CopyButton text={citation} label="Copy citation" />
            </div>
          </div>
        </Card>

        <aside aria-labelledby="also" className="flex flex-col gap-3">
          <SectionTitle id="also">People also check</SectionTitle>
          <ul className="flex flex-col border-t-2 border-ink">
            {day.next_business_day !== undefined && (
              <AlsoLink href={answerPath(calendar.id, day.next_business_day)}>
                Is {formatDate(day.next_business_day)} a {calendar.name} day?
              </AlsoLink>
            )}
            {upcoming !== undefined && (
              <AlsoLink href={answerPath(calendar.id, upcoming.date)}>
                Next {calendar.name} closure: {formatDate(upcoming.date)}
              </AlsoLink>
            )}
            {others.slice(0, 3).map(({ calendar: other }) => (
              <AlsoLink key={other.id} href={answerPath(other.id, day.date)}>
                {other.name} on {formatDate(day.date, "short")}
              </AlsoLink>
            ))}
            <AlsoLink href={`/calendars/${calendar.id}`}>All {calendar.name} closures</AlsoLink>
          </ul>
        </aside>
      </div>

      {around.length > 0 && (
        <section aria-labelledby="around" className="flex flex-col gap-4">
          <SectionTitle id="around">Around this date</SectionTitle>
          <Card className="p-4">
            <DayStrip calendar={calendar} days={around} selected={day.date} />
          </Card>
        </section>
      )}

      <section aria-labelledby="others" className="flex flex-col gap-4">
        <SectionTitle id="others">{formatDate(day.date, "short")} on other calendars</SectionTitle>
        <Card>
          <Table>
            <thead>
              <tr>
                <Th scope="col">Calendar</Th>
                <Th scope="col">Status</Th>
                <Th scope="col">Why</Th>
              </tr>
            </thead>
            <tbody>
              {others.map(({ calendar: other, day: otherDay }) => (
                <tr key={other.id}>
                  <Td>
                    <Link href={answerPath(other.id, day.date)} className="font-semibold">
                      {other.name}
                    </Link>{" "}
                    <span className="font-mono text-xs text-ink-subtle">{other.id}</span>
                  </Td>
                  <Td>
                    {otherDay === undefined ? (
                      <span className="text-ink-muted">No data</span>
                    ) : (
                      <DayStatus kind={dayKind(otherDay, other.weekend)} />
                    )}
                  </Td>
                  <Td className="text-ink-muted">
                    {otherDay === undefined
                      ? "Outside this calendar's sources"
                      : (otherDay.reason ?? (otherDay.is_open ? "Business day" : "Weekend"))}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      </section>

      <section aria-labelledby="code" className="flex flex-col gap-4">
        <SectionTitle id="code">Get this answer in code</SectionTitle>
        <Code>
          {`curl -H "Authorization: Bearer $ZEITWIDE_API_KEY" \\\n  https://api.zeitwide.com/v1/calendars/${calendar.id}/days/${day.date}\n\n`}
          {JSON.stringify(
            {
              date: day.date,
              is_business_day: day.is_open,
              reason: day.reason,
              basis: day.basis,
              dataset_release: datasetRelease,
            },
            null,
            2,
          )}
        </Code>
        <p className="text-sm text-ink-muted">
          Agents get the same answer from the <span className="font-mono">is_business_day</span> MCP
          tool. <Link href="/docs">Developer quickstart</Link>
        </p>
      </section>

      <section aria-labelledby="faq" className="flex flex-col gap-4">
        <SectionTitle id="faq">Questions people ask</SectionTitle>
        <dl className="flex flex-col gap-4">
          <div>
            <dt className="font-semibold">{answerQuestion(calendar, day.date)}</dt>
            <dd className="text-ink-muted">{answerDescription(calendar, day)}</dd>
          </div>
          {day.next_business_day !== undefined && !day.is_open && (
            <div>
              <dt className="font-semibold">When does {calendar.name} settle again?</dt>
              <dd className="text-ink-muted">{formatDate(day.next_business_day, "long")}.</dd>
            </div>
          )}
          <div>
            <dt className="font-semibold">Can I be warned about changes like this?</dt>
            <dd className="text-ink-muted">
              Yes. Sign in and add {calendar.name} to an alert rule for webhook alerts.
            </dd>
          </div>
        </dl>
      </section>
    </article>
  );
}

function AlsoLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <li className="border-b border-line">
      <Link href={href} prefetch className="flex min-h-11 items-center py-2">
        {children}
      </Link>
    </li>
  );
}
