import type { Calendar, DayRange, RangeDay } from "@/api/client";

import { daysAround, isClosure } from "./answers";

/**
 * What the landing page shows, built from API ranges by lookup only: the API decides which days
 * are open and which day is next. Nothing here adds, subtracts or compares dates.
 */

/** The four rails the landing page shows, in this order, when the API lists them. */
export const LANDING_RAILS = ["GB-CHAPS", "US-FEDWIRE", "EU-T2", "US-FEDNOW"] as const;

export interface RailRange {
  calendar: Calendar;
  range: DayRange;
}

/** One rail's answer for one date, ready for the "Check a date" card. */
export interface DateAnswer {
  open: boolean;
  weekend: boolean;
  reason: string | null;
  next: string | null;
  publishers: string[];
}

export interface CheckRail {
  id: string;
  name: string;
  answers: Record<string, DateAnswer>;
}

export interface CheckData {
  rails: CheckRail[];
  /** Dates offered as choices, today first, then the next closures in date order. */
  dates: string[];
  today: string;
  release: string;
}

/** Keeps the rails the API listed, in landing order. */
export function pickRails(ranges: RailRange[]): RailRange[] {
  return LANDING_RAILS.flatMap((id) => ranges.filter((entry) => entry.calendar.id === id));
}

function byDate(range: DayRange): Map<string, RangeDay> {
  return new Map(range.days.map((day) => [day.date, day]));
}

function answerFor(day: RangeDay): DateAnswer {
  return {
    open: day.is_open,
    weekend: day.decided_by === "weekend_rule",
    reason: day.reason ?? null,
    next: day.next_business_day ?? null,
    publishers: [...new Set(day.sources.map((source) => source.publisher))],
  };
}

/**
 * Today, then up to {@link closures} further dates on which any of the rails closes for a holiday,
 * walked in the order the first rail's range lists its days.
 */
export function choiceDates(rails: RailRange[], closures = 3): string[] {
  const first = rails[0];
  if (first === undefined) {
    return [];
  }
  const today = first.range.today;
  const lookups = rails.map((rail) => byDate(rail.range));
  const start = first.range.days.findIndex((day) => day.date === today);
  const dates = [today];
  for (const day of first.range.days.slice(start + 1)) {
    if (dates.length > closures) {
      break;
    }
    const closes = lookups.some((lookup) => {
      const entry = lookup.get(day.date);
      return entry !== undefined && isClosure(entry);
    });
    if (closes) {
      dates.push(day.date);
    }
  }
  return dates;
}

export function checkData(rails: RailRange[], release: string): CheckData | null {
  const first = rails[0];
  if (first === undefined) {
    return null;
  }
  const dates = choiceDates(rails);
  return {
    today: first.range.today,
    release,
    dates,
    rails: rails.map(({ calendar, range }) => {
      const lookup = byDate(range);
      const answers: Record<string, DateAnswer> = {};
      for (const date of dates) {
        const day = lookup.get(date);
        if (day !== undefined) {
          answers[date] = answerFor(day);
        }
      }
      return { id: calendar.id, name: calendar.name, answers };
    }),
  };
}

export interface BandRail {
  calendar: Calendar;
  days: RangeDay[];
}

/** Today and the next {@link length} - 1 days of each rail, as the API listed them. */
export function bandRails(rails: RailRange[], length = 21): BandRail[] {
  return rails.map(({ calendar, range }) => ({
    calendar,
    days: daysAround(range, range.today, 0, length - 1),
  }));
}

/** A sentence for screen readers: which days each rail closes for a holiday in the band. */
export function bandSummary(
  rails: BandRail[],
  describe: (isoDate: string) => string,
  weekendHoliday: (day: RangeDay, calendar: Calendar) => boolean,
): string {
  return rails
    .map(({ calendar, days }) => {
      const closed = days
        .filter((day) => isClosure(day) && !weekendHoliday(day, calendar))
        .map((day) => describe(day.date));
      return closed.length === 0
        ? `${calendar.name} has no holiday closures.`
        : `${calendar.name} closed ${closed.join(", ")}.`;
    })
    .join(" ");
}

export interface UpcomingClosure {
  date: string;
  closed: { id: string; name: string; reason: string }[];
  stillOpen: string[];
}

/**
 * The next dates, after today, on which any rail closes for a holiday: which rails close and which
 * stay open. Walks the first rail's days in the order the API listed them.
 */
export function upcomingClosures(rails: RailRange[], limit = 4): UpcomingClosure[] {
  const first = rails[0];
  if (first === undefined) {
    return [];
  }
  const lookups = rails.map((rail) => ({
    id: rail.calendar.id,
    name: rail.calendar.name,
    days: byDate(rail.range),
  }));
  const start = first.range.days.findIndex((day) => day.date === first.range.today);
  const found: UpcomingClosure[] = [];
  for (const { date } of first.range.days.slice(start + 1)) {
    if (found.length === limit) {
      break;
    }
    const closed: UpcomingClosure["closed"] = [];
    const stillOpen: string[] = [];
    for (const { id, name, days } of lookups) {
      const day = days.get(date);
      if (day !== undefined && isClosure(day)) {
        closed.push({ id, name, reason: day.reason ?? "Closed" });
      } else if (day?.is_open === true) {
        stillOpen.push(name);
      }
    }
    if (closed.length > 0) {
      found.push({ date, closed, stillOpen });
    }
  }
  return found;
}

/** "All 5 rails settle today.", "3 of 5 rails settle today." or "No rail settles today." */
export function settleHeadline(rails: RailRange[]): string {
  const open = rails.filter(
    ({ range }) => range.days.find((day) => day.date === range.today)?.is_open === true,
  );
  if (rails.length > 0 && open.length === rails.length) {
    return `${rails.length === 1 ? "The rail settles" : `All ${rails.length} rails settle`} today.`;
  }
  return open.length === 0
    ? "No rail settles today."
    : `${open.length} of ${rails.length} rails settle today.`;
}
