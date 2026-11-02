import "server-only";

import type { Calendar, CalendarList, RangeDay } from "@/api/client";

import { findCalendar, isClosure } from "./answers";
import { getCalendars, getDays, getYearDays } from "./api-server";
import { isIsoDate } from "./format";
import {
  bandAround,
  bandNeeds,
  buildYears,
  calendarPublishers,
  collectSources,
  comparePairs,
  compareYear,
  coveredYears,
  daysAfter,
  isCanonicalPair,
  parsePair,
  summarizeYear,
  yearOf,
  type Comparison,
  type SourceSummary,
  type YearSummary,
} from "./public-pages";

/**
 * Everything the public pages show, read from the API's cached ranges. Each calendar-year is one
 * cached call, shared by the year page, every answer page in that year and the compare pages.
 */

export interface CalendarYear {
  calendar: Calendar;
  summary: YearSummary;
}

async function yearDays(calendar: Calendar, year: number): Promise<RangeDay[]> {
  return (await getYearDays(calendar.id, calendar.revision, year))?.days ?? [];
}

/** The calendar's date now, as the API's default window reports it. */
async function todayOn(calendar: Calendar): Promise<string | null> {
  return (await getDays(calendar.id, calendar.revision))?.today ?? null;
}

async function siteToday(list: CalendarList): Promise<string | null> {
  const first = list.calendars[0];
  return first === undefined ? null : todayOn(first);
}

// ---- /calendars/{id}/{yyyy-mm-dd} ----

export interface AnswerPageData {
  calendar: Calendar;
  day: RangeDay;
  band: RangeDay[];
  others: { calendar: Calendar; day: RangeDay | undefined }[];
  release: string;
  yearPublishers: string[];
}

/** Everything an answer page shows, or null when the calendar or date has no answer. */
export async function answerPageData(id: string, date: string): Promise<AnswerPageData | null> {
  if (!isIsoDate(date)) {
    return null;
  }
  const list = await getCalendars();
  const calendar = findCalendar(list, id);
  if (calendar === undefined) {
    return null;
  }
  const year = yearOf(date);
  const range = await getYearDays(calendar.id, calendar.revision, year);
  const day = range?.days.find((candidate) => candidate.date === date);
  if (range === null || day === undefined) {
    return null;
  }
  const needs = bandNeeds(range.days, date);
  const timeline = [
    ...(needs.previous ? await yearDays(calendar, year - 1) : []),
    ...range.days,
    ...(needs.next ? await yearDays(calendar, year + 1) : []),
  ];
  const others = await Promise.all(
    list.calendars
      .filter((other) => other.id !== calendar.id)
      .map(async (other) => ({
        calendar: other,
        day: (await yearDays(other, year)).find((candidate) => candidate.date === date),
      })),
  );
  return {
    calendar,
    day,
    band: bandAround(timeline, date),
    others,
    release: range.dataset_release,
    yearPublishers: calendarPublishers(range.days),
  };
}

// ---- /calendars/{id}/{year} ----

export interface YearPageData {
  calendar: Calendar;
  summary: YearSummary;
  release: string;
  /** The covered years next to this one, with their own counts, for links. */
  neighbours: YearSummary[];
  /** Other calendars that year, when compare pages exist for it. */
  comparisons: { calendar: Calendar; comparison: Comparison }[];
}

export async function yearPageData(id: string, year: number): Promise<YearPageData | null> {
  const list = await getCalendars();
  const calendar = findCalendar(list, id);
  if (calendar === undefined) {
    return null;
  }
  const range = await getYearDays(calendar.id, calendar.revision, year);
  if (range === null || range.days.length === 0) {
    return null;
  }
  const built = buildYears(range.today);
  const covered = coveredYears(calendar, built);
  const neighbours = await Promise.all(
    [year - 1, year + 1]
      .filter((other) => covered.includes(other))
      .map(async (other) => summarizeYear(calendar, other, await yearDays(calendar, other))),
  );
  const comparisons = built.includes(year)
    ? await Promise.all(
        list.calendars
          .filter((other) => other.id !== calendar.id)
          .map(async (other) => ({
            calendar: other,
            comparison: compareYear(calendar, range.days, other, await yearDays(other, year), year),
          })),
      )
    : [];
  return {
    calendar,
    summary: summarizeYear(calendar, year, range.days),
    release: range.dataset_release,
    neighbours,
    comparisons,
  };
}

// ---- /calendars/{id} ----

export interface CalendarPageData {
  calendar: Calendar;
  today: string;
  todayAnswer: RangeDay | undefined;
  release: string;
  nextClosures: RangeDay[];
  years: YearSummary[];
  sources: SourceSummary[];
}

const NEXT_CLOSURES = 5;

export async function calendarPageData(id: string): Promise<CalendarPageData | null> {
  const list = await getCalendars();
  const calendar = findCalendar(list, id);
  if (calendar === undefined) {
    return null;
  }
  const today = await todayOn(calendar);
  if (today === null) {
    return null;
  }
  const years = await Promise.all(
    coveredYears(calendar, buildYears(today)).map(async (year) => ({
      year,
      days: await yearDays(calendar, year),
    })),
  );
  const timeline = years.flatMap((entry) => entry.days);
  return {
    calendar,
    today,
    todayAnswer: timeline.find((day) => day.date === today),
    release: list.dataset_release,
    nextClosures: daysAfter(timeline, today).filter(isClosure).slice(0, NEXT_CLOSURES),
    years: years.map((entry) => summarizeYear(calendar, entry.year, entry.days)),
    sources: collectSources([{ calendar, days: timeline }]),
  };
}

// ---- /compare/{idA}-vs-{idB}/{year} ----

export type ComparePageData =
  | { kind: "redirect"; idA: string; idB: string }
  | {
      kind: "page";
      a: Calendar;
      b: Calendar;
      year: number;
      comparison: Comparison;
      release: string;
    };

export async function comparePageData(pair: string, year: number): Promise<ComparePageData | null> {
  const ids = parsePair(pair);
  if (ids === null) {
    return null;
  }
  const list = await getCalendars();
  const a = findCalendar(list, ids[0]);
  const b = findCalendar(list, ids[1]);
  if (a === undefined || b === undefined) {
    return null;
  }
  if (!isCanonicalPair(a.id, b.id)) {
    return { kind: "redirect", idA: b.id, idB: a.id };
  }
  if (!coveredYears(a, [year]).includes(year) || !coveredYears(b, [year]).includes(year)) {
    return null;
  }
  const [rangeA, rangeB] = await Promise.all([
    getYearDays(a.id, a.revision, year),
    getYearDays(b.id, b.revision, year),
  ]);
  if (rangeA === null || rangeB === null) {
    return null;
  }
  return {
    kind: "page",
    a,
    b,
    year,
    comparison: compareYear(a, rangeA.days, b, rangeB.days, year),
    release: rangeA.dataset_release,
  };
}

// ---- /sources ----

export async function sourcesPageData(): Promise<{
  sources: SourceSummary[];
  release: string;
  years: number[];
}> {
  const list = await getCalendars();
  const today = await siteToday(list);
  const years = today === null ? [] : buildYears(today);
  const entries = await Promise.all(
    list.calendars.map(async (calendar) => ({
      calendar,
      days: (await Promise.all(years.map((year) => yearDays(calendar, year)))).flat(),
    })),
  );
  return { sources: collectSources(entries), release: list.dataset_release, years };
}

// ---- Static params ----

/** Answer pages from today to the end of each calendar's default window (today plus 90 days). */
export async function upcomingDateParams(): Promise<{ id: string; date: string }[]> {
  const { calendars } = await getCalendars();
  const ranges = await Promise.all(
    calendars.map(async (calendar) => ({
      id: calendar.id,
      range: await getDays(calendar.id, calendar.revision),
    })),
  );
  return ranges.flatMap(({ id, range }) => {
    if (range === null) {
      return [];
    }
    const today = range.days.find((day) => day.date === range.today);
    const upcoming = daysAfter(range.days, range.today);
    return [...(today === undefined ? [] : [today]), ...upcoming].map((day) => ({
      id,
      date: day.date,
    }));
  });
}

/** Year pages for every year each calendar covers (the current and next for FedNow). */
export async function yearParams(): Promise<{ id: string; year: number }[]> {
  const list = await getCalendars();
  const today = await siteToday(list);
  const built = today === null ? [] : buildYears(today);
  return list.calendars.flatMap((calendar) =>
    coveredYears(calendar, built).map((year) => ({ id: calendar.id, year })),
  );
}

/** Every pair of calendars for the current and next year. */
export async function compareParams(): Promise<{ idA: string; idB: string; year: number }[]> {
  const list = await getCalendars();
  const today = await siteToday(list);
  const built = today === null ? [] : buildYears(today);
  return comparePairs(list.calendars.map((calendar) => calendar.id)).flatMap(([idA, idB]) =>
    built.map((year) => ({ idA, idB, year })),
  );
}

// ---- /calendars ----

/** Each calendar's closures this year, for the index's links. */
export async function calendarYearSummaries(): Promise<CalendarYear[]> {
  const list = await getCalendars();
  const today = await siteToday(list);
  if (today === null) {
    return [];
  }
  const year = yearOf(today);
  return Promise.all(
    list.calendars.map(async (calendar) => ({
      calendar,
      summary: summarizeYear(calendar, year, await yearDays(calendar, year)),
    })),
  );
}
