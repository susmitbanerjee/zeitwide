import type { Calendar, CalendarList, DayRange, RangeDay } from "@/api/client";

/**
 * Builds answer pages from API data by lookup only: finding a date in a list the API returned,
 * or slicing that list. Business-day decisions (open, next, previous) are always the API's.
 */

export function findCalendar(list: CalendarList, id: string): Calendar | undefined {
  return list.calendars.find((calendar) => calendar.id === id);
}

export function findDay(range: DayRange | null, date: string): RangeDay | undefined {
  return range?.days.find((day) => day.date === date);
}

/** Up to {@link before} days before and {@link after} days after the date, as the API listed them. */
export function daysAround(range: DayRange, date: string, before = 7, after = 13): RangeDay[] {
  const index = range.days.findIndex((day) => day.date === date);
  if (index < 0) {
    return [];
  }
  return range.days.slice(Math.max(0, index - before), index + after + 1);
}

/** The next listed closure after the date (an entry that closes the calendar), if any. */
export function nextClosure(range: DayRange, date: string): RangeDay | undefined {
  const index = range.days.findIndex((day) => day.date === date);
  return range.days.slice(index + 1).find(isClosure);
}

/** Closures in the API's window: entries that close the calendar, in date order. */
export function closures(range: DayRange): RangeDay[] {
  return range.days.filter(isClosure);
}

export function isClosure(day: RangeDay): boolean {
  return day.decided_by === "entry" && !day.is_open;
}

export type DayKind = "open" | "closed" | "weekend" | "weekend-holiday";

/**
 * How a day is drawn. A holiday that falls on one of the calendar's weekend days (as the API lists
 * them) is drawn as a weekend with the holiday marked, since it changes nothing.
 */
export function dayKind(day: RangeDay, weekend: Calendar["weekend"]): DayKind {
  if (day.is_open) {
    return "open";
  }
  if (day.decided_by === "weekend_rule") {
    return "weekend";
  }
  return (weekend as string[]).includes(weekdayName(day.date)) ? "weekend-holiday" : "closed";
}

const weekdayFormat = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "long" });

/** The API's weekday name for an ISO date: "monday" … "sunday". */
export function weekdayName(isoDate: string): string {
  return weekdayFormat.format(new Date(`${isoDate}T00:00:00Z`)).toLowerCase();
}

export function calendarLabel(calendar: Pick<Calendar, "name" | "id">): string {
  return calendar.name;
}

/** Rails first, then public holiday calendars, then exchanges; each group in API order. */
export function groupCalendars(calendars: Calendar[]): { label: string; calendars: Calendar[] }[] {
  const groups = [
    { kind: "rail", label: "Payment rails" },
    { kind: "public", label: "Public holidays" },
    { kind: "exchange", label: "Exchanges" },
  ] as const;
  return groups
    .map((group) => ({
      label: group.label,
      calendars: calendars.filter((calendar) => calendar.kind === group.kind),
    }))
    .filter((group) => group.calendars.length > 0);
}

/** One static page per calendar per date in the API's default window. */
export function answerPageParams(
  ranges: { calendarId: string; range: DayRange | null }[],
): { id: string; date: string }[] {
  return ranges.flatMap(({ calendarId, range }) =>
    (range?.days ?? []).map((day) => ({ id: calendarId, date: day.date })),
  );
}
