"use client";

import { useQueries, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type Calendar, type DayRange } from "@/api/client";
import { DayStatus } from "@/components/day-status";
import { Zeitband } from "@/components/landing/zeitband";
import { PageHeading } from "@/components/app-chrome";
import { Card, ErrorText, Input, Label, SectionTitle, Select } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { dayKind, nextClosure } from "@/lib/answers";
import { formatDate } from "@/lib/format";
import { bandRails, settleHeadline, upcomingClosures, type RailRange } from "@/lib/landing";
import { errorMessage, useCalendars } from "@/lib/queries";
import { answerPath } from "@/lib/seo";

/** The weeks the band shows, from the API's today. */
const BAND_DAYS = 70;

/**
 * P2: what is open today on every rail, what is coming, and a quick check. Every date and every
 * open-or-closed is the API's: this page looks answers up in the ranges the API returned.
 */
export function Today() {
  const calendars = useCalendars();
  const rails = (calendars.data?.calendars ?? []).filter((calendar) => calendar.kind === "rail");
  const ranges = useQueries({
    queries: rails.map((calendar) => ({
      queryKey: ["days", calendar.id, undefined, undefined],
      queryFn: async () =>
        unwrap(
          await browserApi.GET("/v1/calendars/{id}/days", {
            params: { path: { id: calendar.id }, query: {} },
          }),
        ),
    })),
  });
  const loaded: RailRange[] = rails.flatMap((calendar, index) => {
    const range = ranges[index]?.data;
    return range === undefined ? [] : [{ calendar, range }];
  });
  const failed =
    ranges.find((query) => query.isError) ?? (calendars.isError ? calendars : undefined);
  const first = loaded[0];
  if (first === undefined) {
    return (
      <div className="flex flex-col gap-6">
        <PageHeading title="Today" />
        {failed === undefined ? (
          <p role="status" className="text-ink-muted">
            Loading your rails…
          </p>
        ) : (
          <ErrorText>{errorMessage(failed.error)}</ErrorText>
        )}
      </div>
    );
  }
  const upcoming = upcomingClosures(loaded, 6);
  return (
    <div className="flex flex-col gap-7">
      <div className="flex flex-col gap-1">
        <span className="font-mono text-sm text-ink-muted">
          {formatDate(first.range.today, "medium")}
        </span>
        <PageHeading title={settleHeadline(loaded)} />
      </div>

      <section aria-label="Right now" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        {loaded.map(({ calendar, range }) => (
          <RailCard key={calendar.id} calendar={calendar} range={range} />
        ))}
      </section>

      <section className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <Card className="flex flex-col">
          <SectionTitle className="border-b border-line px-5 py-4">Coming up</SectionTitle>
          {upcoming.length === 0 ? (
            <p className="p-5 text-ink-muted">No closures in the days the API lists.</p>
          ) : (
            <ul>
              {upcoming.slice(0, 4).map((closure) => (
                <li
                  key={closure.date}
                  className="grid gap-x-5 gap-y-1 border-b border-line px-5 py-4 last:border-b-0 sm:grid-cols-[9rem_minmax(0,1fr)_auto]"
                >
                  <span className="font-mono text-sm leading-6">
                    {formatDate(closure.date, "medium")}
                  </span>
                  <div className="flex flex-col gap-0.5">
                    <span className="font-semibold">
                      {closure.closed.map((rail) => rail.name).join(", ")} closed:{" "}
                      {closure.closed[0]?.reason}
                    </span>
                    {closure.stillOpen.length > 0 && (
                      <span className="text-sm text-ink-muted">
                        {closure.stillOpen.join(", ")}{" "}
                        {closure.stillOpen.length === 1 ? "stays" : "stay"} open.
                      </span>
                    )}
                  </div>
                  <Link href="/app/calculator" className="text-sm font-semibold">
                    Plan around it
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <QuickCheck rails={rails} />
      </section>

      <section aria-labelledby="band" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-3">
          <SectionTitle id="band">Next 10 weeks on your rails</SectionTitle>
          <Link href="/app/explorer" className="text-sm font-semibold">
            Open the timetable
          </Link>
        </div>
        <Zeitband
          rails={bandRails(loaded, BAND_DAYS)}
          today={first.range.today}
          title="The next 10 weeks"
        />
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[40rem] border-collapse text-left text-[14.5px]">
            <caption className="sr-only">Closures in the next ten weeks</caption>
            <thead>
              <tr className="text-xs tracking-[0.06em] text-ink-subtle uppercase">
                {["Date", "Closed", "Still open", "Reason"].map((heading) => (
                  <th key={heading} scope="col" className="border-b border-line px-5 py-2.5">
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {upcoming.map((closure) => (
                <tr key={closure.date}>
                  <td className="border-b border-line px-5 py-3 font-mono text-sm">
                    {formatDate(closure.date, "short")}
                  </td>
                  <td className="border-b border-line px-3 py-3">
                    {closure.closed.map((rail) => rail.name).join(", ")}
                  </td>
                  <td className="border-b border-line px-3 py-3">
                    {closure.stillOpen.join(", ") || "None"}
                  </td>
                  <td className="border-b border-line px-5 py-3">{closure.closed[0]?.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </section>
    </div>
  );
}

function RailCard({ calendar, range }: { calendar: Calendar; range: DayRange }) {
  const today = range.days.find((day) => day.date === range.today);
  const upcoming = nextClosure(range, range.today);
  return (
    <Card className="flex flex-col gap-3 p-5">
      <div className="flex items-start justify-between gap-2">
        <div className="flex flex-col">
          <Link
            href={answerPath(calendar.id, range.today)}
            className="font-semibold text-ink no-underline hover:underline"
          >
            {calendar.name}
          </Link>
          <span className="font-mono text-xs text-ink-subtle">{calendar.id}</span>
        </div>
        {today !== undefined && <DayStatus kind={dayKind(today, calendar.weekend)} />}
      </div>
      <p className="text-sm text-ink-muted">
        {today?.is_open === true
          ? "Settling today."
          : `Not settling today${today?.reason === undefined ? "" : `: ${today.reason}`}.`}
      </p>
      {today?.next_business_day !== undefined && !today.is_open && (
        <p className="text-sm">
          Next business day{" "}
          <span className="font-mono">{formatDate(today.next_business_day, "short")}</span>
        </p>
      )}
      <p className="mt-auto text-sm text-ink-muted">
        {upcoming === undefined ? (
          "No closure listed ahead."
        ) : (
          <>
            Next closure{" "}
            <Link href={answerPath(calendar.id, upcoming.date)} className="font-mono">
              {formatDate(upcoming.date, "short")}
            </Link>
            {upcoming.reason === undefined ? "" : ` · ${upcoming.reason}`}
          </>
        )}
      </p>
    </Card>
  );
}

function QuickCheck({ rails }: { rails: Calendar[] }) {
  const id = useId();
  const [calendar, setCalendar] = useState(rails[0]?.id ?? "");
  const [date, setDate] = useState("");
  const rail = rails.find((entry) => entry.id === calendar) ?? rails[0];
  const answer = useQuery({
    queryKey: ["day", rail?.id, date, "now"],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/calendars/{id}/days/{date}", {
          params: { path: { id: rail?.id ?? "", date } },
        }),
      ),
    enabled: rail !== undefined && date !== "",
  });
  return (
    <Card className="flex flex-col gap-4 p-5">
      <SectionTitle>Quick check</SectionTitle>
      <form className="flex flex-col gap-3" onSubmit={(event) => event.preventDefault()}>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-rail`}>Rail</Label>
          <Select
            id={`${id}-rail`}
            value={rail?.id ?? ""}
            onChange={(event) => setCalendar(event.target.value)}
          >
            {rails.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.name} · {entry.id}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-date`}>Date</Label>
          <Input
            id={`${id}-date`}
            type="date"
            value={date}
            onChange={(event) => setDate(event.target.value)}
          />
        </div>
      </form>
      <div aria-live="polite" className="flex flex-col gap-1 text-[15px]">
        {answer.isError && <ErrorText>{errorMessage(answer.error)}</ErrorText>}
        {answer.data !== undefined && rail !== undefined && (
          <>
            <p>
              <b>{answer.data.is_business_day ? "Open." : "Closed."}</b>{" "}
              {answer.data.calendars[0]?.reason ??
                (answer.data.is_business_day ? "Business day." : "Weekend.")}
            </p>
            <Link href={answerPath(rail.id, date)} className="text-sm font-semibold">
              Open the full answer
            </Link>
          </>
        )}
      </div>
    </Card>
  );
}
