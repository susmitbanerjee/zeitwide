"use client";

import { useQueries, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type Calendar, type DayRange } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { CalendarPicker } from "@/components/calendar-picker";
import { DayLegend, DayStatus } from "@/components/day-status";
import { Card, ErrorText, Input, Label, Status } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { dayKind, findDay } from "@/lib/answers";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/format";
import { errorMessage, useCalendars, useDays } from "@/lib/queries";
import { answerPath } from "@/lib/seo";

/** How many listed days the default view shows, starting from the API's "today". */
const DEFAULT_SPAN = 21;

/**
 * A2: every chosen calendar as a row of days. The dates are the API's: the default range starts
 * at the "today" it reports and ends 21 listed days later; the visitor can type any other range.
 */
export function Explorer() {
  const calendars = useCalendars();
  const all = calendars.data?.calendars ?? [];
  const [chosen, setChosen] = useState<string[] | null>(null);
  const selected = chosen ?? all.filter((c) => c.kind === "rail").map((c) => c.id);
  const reference = all.find((c) => c.coverage.type === "unlimited") ?? all[0];
  const defaults = useDays(reference?.id ?? "");
  const defaultFrom = defaults.data?.today;
  const todayIndex = defaults.data?.days.findIndex((d) => d.date === defaultFrom) ?? -1;
  const defaultTo =
    todayIndex < 0 ? undefined : defaults.data?.days[todayIndex + DEFAULT_SPAN - 1]?.date;
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const from = range?.from ?? defaultFrom;
  const to = range?.to ?? defaultTo;
  const [picked, setPicked] = useState<string | null>(null);
  const id = useId();

  const ranges = useQueries({
    queries: selected.map((calendarId) => ({
      queryKey: ["days", calendarId, from, to],
      queryFn: async () =>
        unwrap(
          await browserApi.GET("/v1/calendars/{id}/days", {
            params: { path: { id: calendarId }, query: { from: from ?? "", to: to ?? "" } },
          }),
        ),
      enabled: from !== undefined && to !== undefined,
    })),
  });
  const rows = selected
    .map((calendarId, index) => ({
      calendar: all.find((c) => c.id === calendarId),
      range: ranges[index]?.data,
    }))
    .filter(
      (row): row is { calendar: Calendar; range: DayRange | undefined } =>
        row.calendar !== undefined,
    );
  // The columns: the longest list the API returned for this range.
  const columns = rows
    .map((row) => row.range?.days.map((d) => d.date) ?? [])
    .reduce<string[]>((longest, dates) => (dates.length > longest.length ? dates : longest), []);
  const selectedDate = picked !== null && columns.includes(picked) ? picked : (columns[0] ?? null);
  const failed = ranges.find((query) => query.isError);

  return (
    <div className="flex flex-col gap-6">
      <PageHeading
        title="Explorer"
        lead="Every calendar you choose, one row each. Select a day to see why it is open or closed."
      />
      <Card className="flex flex-col gap-4 p-5">
        <CalendarPicker calendars={all} selected={selected} onChange={setChosen} />
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            setRange({ from: String(data.get("from")), to: String(data.get("to")) });
          }}
        >
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-from`}>From</Label>
            <Input
              id={`${id}-from`}
              name="from"
              type="date"
              required
              defaultValue={from}
              key={`f-${from}`}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-to`}>To</Label>
            <Input
              id={`${id}-to`}
              name="to"
              type="date"
              required
              defaultValue={to}
              key={`t-${to}`}
            />
          </div>
          <button
            type="submit"
            className="min-h-11 rounded-md border border-line-strong bg-surface px-4 font-semibold hover:bg-surface-sunken"
          >
            Show these dates
          </button>
        </form>
      </Card>

      {calendars.isError && <ErrorText>{errorMessage(calendars.error)}</ErrorText>}
      {failed !== undefined && <ErrorText>{errorMessage(failed.error)}</ErrorText>}
      {columns.length === 0 && !failed && <Status>Loading the calendars…</Status>}

      {columns.length > 0 && (
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
          <Card className="overflow-x-auto p-4">
            <table className="border-separate border-spacing-1 text-sm">
              <caption className="sr-only">
                Days from {formatDate(columns[0] ?? "")} to {formatDate(columns.at(-1) ?? "")}
              </caption>
              <thead>
                <tr>
                  <th scope="col" className="sr-only">
                    Calendar
                  </th>
                  {columns.map((date) => (
                    <th
                      key={date}
                      scope="col"
                      className="min-w-11 font-mono text-[11px] font-normal text-ink-muted"
                    >
                      <span className="block">{formatDate(date, "weekday")}</span>
                      <span className="block text-[13px] text-ink">{formatDate(date, "day")}</span>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map(({ calendar, range: days }) => (
                  <tr key={calendar.id}>
                    <th scope="row" className="pr-3 text-left whitespace-nowrap">
                      <span className="block font-semibold">{calendar.name}</span>
                      <span className="block font-mono text-[11px] font-normal text-ink-subtle">
                        {calendar.id}
                      </span>
                    </th>
                    {columns.map((date) => {
                      const day = findDay(days ?? null, date);
                      const kind = day === undefined ? undefined : dayKind(day, calendar.weekend);
                      return (
                        <td key={date}>
                          <button
                            type="button"
                            onClick={() => setPicked(date)}
                            aria-pressed={selectedDate === date}
                            aria-label={`${calendar.name}, ${formatDate(date, "long")}: ${
                              day === undefined
                                ? "no data"
                                : day.is_open
                                  ? "open"
                                  : (day.reason ?? "weekend")
                            }`}
                            className={cn(
                              "block h-11 w-11 rounded",
                              kind === "open" && "border border-line bg-surface",
                              kind === "closed" && "bg-closed",
                              kind === "weekend" && "hatch border border-line",
                              kind === "weekend-holiday" && "hatch border-2 border-closed",
                              kind === undefined && "bg-surface-sunken",
                              selectedDate === date && "outline-2 outline-offset-1 outline-ink",
                            )}
                          />
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="mt-4">
              <DayLegend />
            </div>
          </Card>
          {selectedDate !== null && <SelectedDay date={selectedDate} rows={rows} />}
        </div>
      )}
    </div>
  );
}

function SelectedDay({
  date,
  rows,
}: {
  date: string;
  rows: { calendar: Calendar; range: DayRange | undefined }[];
}) {
  const ids = rows.map((row) => row.calendar.id);
  const combined = useQuery({
    queryKey: ["check", ids, date],
    queryFn: async () =>
      unwrap(await browserApi.POST("/v1/business-days/check", { body: { calendars: ids, date } })),
    enabled: ids.length > 0,
  });
  return (
    <Card className="flex flex-col" aria-live="polite">
      <section
        aria-labelledby="selected-day"
        className="flex flex-col gap-1 border-b border-line p-5"
      >
        <h2 id="selected-day" className="font-mono text-xl">
          {formatDate(date, "long")}
        </h2>
        <p className="font-semibold">
          {combined.data === undefined
            ? "Checking every calendar…"
            : combined.data.is_business_day
              ? "Every chosen calendar is open"
              : "Not open on every chosen calendar"}
        </p>
      </section>
      <ul>
        {rows.map(({ calendar, range }) => {
          const day = findDay(range ?? null, date);
          return (
            <li key={calendar.id} className="flex flex-col gap-1 border-b border-line px-5 py-3">
              <div className="flex items-center justify-between gap-2">
                <Link href={answerPath(calendar.id, date)} className="font-semibold">
                  {calendar.name}
                </Link>
                {day !== undefined && <DayStatus kind={dayKind(day, calendar.weekend)} />}
              </div>
              <p className="text-sm text-ink-muted">
                {day === undefined
                  ? "Outside this calendar's sources"
                  : (day.reason ?? (day.is_open ? "Business day" : "Weekend"))}
              </p>
              {day !== undefined && day.decided_by === "entry" && (
                <Link href={`/app/evidence/${calendar.id}/${date}`} className="text-sm">
                  Evidence
                </Link>
              )}
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
