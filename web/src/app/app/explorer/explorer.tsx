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
import { dayKind, findDay, groupCalendars } from "@/lib/answers";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/format";
import { errorMessage, useCalendars, useDays } from "@/lib/queries";
import { answerPath } from "@/lib/seo";

/** How many listed days a preset shows, starting from the API's "today". */
const SPANS = [
  { days: 14, label: "2 weeks" },
  { days: 21, label: "3 weeks" },
  { days: 90, label: "Quarter" },
] as const;

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
  const [span, setSpan] = useState<number>(21);
  const defaultTo = todayIndex < 0 ? undefined : defaults.data?.days[todayIndex + span - 1]?.date;
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
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <PageHeading
          title="Timetable"
          lead="Every calendar you follow, one row each. Select a day to see why."
        />
        <div
          role="group"
          aria-label="Range"
          className="inline-flex gap-0.5 rounded-lg bg-[#e7e8e5] p-[3px]"
        >
          {SPANS.map((option) => (
            <button
              key={option.days}
              type="button"
              aria-pressed={range === null && span === option.days}
              onClick={() => {
                setRange(null);
                setSpan(option.days);
              }}
              className={cn(
                "min-h-11 rounded-md px-3.5 text-sm",
                range === null && span === option.days
                  ? "bg-surface font-semibold ring-1 ring-line-strong"
                  : "hover:bg-surface/60",
              )}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
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
        <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_21rem]">
          <Card className="overflow-x-auto px-5 pt-4 pb-5">
            <table className="w-full min-w-[40rem] border-separate border-spacing-x-0.5 border-spacing-y-1 text-sm">
              <caption className="sr-only">
                Days from {formatDate(columns[0] ?? "")} to {formatDate(columns.at(-1) ?? "")}
              </caption>
              <thead>
                <tr aria-hidden="true">
                  <td />
                  {months(columns).map((month) => (
                    <td
                      key={month.label + month.first}
                      colSpan={month.count}
                      className="border-b border-ink pb-1 text-left text-xs font-semibold"
                    >
                      {month.count > 2 ? month.label : ""}
                    </td>
                  ))}
                </tr>
                <tr>
                  <th scope="col" className="sr-only">
                    Calendar
                  </th>
                  {columns.map((date) => (
                    <th
                      key={date}
                      scope="col"
                      className={cn(
                        "min-w-6 rounded-[3px] py-1 font-mono text-[11px] font-normal",
                        selectedDate === date ? "bg-ink text-white" : "text-ink-muted",
                      )}
                    >
                      <span className="block">{formatDate(date, "weekday").slice(0, 2)}</span>
                      <span className="block text-[13px]">{formatDate(date, "day")}</span>
                    </th>
                  ))}
                </tr>
              </thead>
              {groupRows(rows).map((group) => (
                <tbody key={group.label}>
                  <tr>
                    <th
                      scope="colgroup"
                      colSpan={columns.length + 1}
                      className="pt-3 text-left text-[11.5px] font-semibold tracking-[0.08em] text-ink-subtle uppercase"
                    >
                      {group.label}
                    </th>
                  </tr>
                  {group.rows.map(({ calendar, range: days }) => (
                    <tr key={calendar.id}>
                      <th scope="row" className="min-w-40 pr-3 text-left whitespace-nowrap">
                        <span className="block text-sm font-semibold">{calendar.name}</span>
                        <span className="block font-mono text-[11.5px] font-normal text-ink-subtle">
                          {calendar.id}
                        </span>
                      </th>
                      {columns.map((date) => {
                        const day = findDay(days ?? null, date);
                        const kind = day === undefined ? undefined : dayKind(day, calendar.weekend);
                        return (
                          <td key={date} className="p-0">
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
                                "block h-10 w-full min-w-6 rounded-[3px]",
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
              ))}
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

/** Consecutive dates that share a month, in the order given: the labels above the columns. */
export function months(dates: string[]): { label: string; first: string; count: number }[] {
  const runs: { label: string; first: string; count: number }[] = [];
  for (const date of dates) {
    const label = formatDate(date, "month");
    const last = runs.at(-1);
    if (last?.label === label) {
      last.count += 1;
    } else {
      runs.push({ label, first: date, count: 1 });
    }
  }
  return runs;
}

type Row = { calendar: Calendar; range: DayRange | undefined };

/** Payment rails, then public holidays, then exchanges, each group in the order chosen. */
function groupRows(rows: Row[]): { label: string; rows: Row[] }[] {
  return groupCalendars(rows.map((row) => row.calendar)).map((group) => ({
    label: group.label,
    rows: rows.filter((row) => group.calendars.some((c) => c.id === row.calendar.id)),
  }));
}
