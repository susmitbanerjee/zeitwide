import type { Calendar, RangeDay, Source } from "@/api/client";

import { dayKind, isClosure, type DayKind } from "./answers";
import { formatDate, formatInstant, plural } from "./format";
import { absoluteUrl, answerPath } from "./seo";

/**
 * The words and tables of the public pages, built from API data by lookup only. The API decides
 * which days are open, which day is next and which dates a calendar covers; nothing here adds,
 * subtracts or compares dates. Years are plain numbers: the page for 2027 asks the API for
 * "2027-01-01" to "2027-12-31".
 */

/** A four-digit year segment, as in /calendars/GB-CHAPS/2027. */
export function isYear(value: string): boolean {
  return /^\d{4}$/.test(value);
}

export function yearOf(isoDate: string): number {
  return Number(isoDate.slice(0, 4));
}

/** The first and last day of a year, written out for the API's range query. */
export function yearRange(year: number): { from: string; to: string } {
  const padded = String(year).padStart(4, "0");
  return { from: `${padded}-01-01`, to: `${padded}-12-31` };
}

/**
 * The years a calendar answers for, from its coverage. A calendar that closes on no holiday covers
 * every year, so it gets {@link openYears} (the years the site builds pages for).
 */
export function coveredYears(calendar: Pick<Calendar, "coverage">, openYears: number[]): number[] {
  const { coverage } = calendar;
  if (coverage.type === "unlimited") {
    return openYears;
  }
  if (coverage.type === "none" || coverage.from === undefined || coverage.to === undefined) {
    return [];
  }
  const years: number[] = [];
  for (let year = yearOf(coverage.from); year <= yearOf(coverage.to); year++) {
    years.push(year);
  }
  return years;
}

/** The year a page is about and the one after: the years with prebuilt year and compare pages. */
export function buildYears(today: string): number[] {
  const year = yearOf(today);
  return [year, year + 1];
}

export function yearPath(calendarId: string, year: number): string {
  return `/calendars/${encodeURIComponent(calendarId)}/${year}`;
}

export function calendarPath(calendarId: string): string {
  return `/calendars/${encodeURIComponent(calendarId)}`;
}

// ---- Zeitband ----

export const BAND_DAYS = 28;
const BAND_DAYS_BEFORE = 7;

/**
 * The 28 days the API listed around a date: a week before it and three after, shifted to stay
 * inside the list near its ends.
 */
export function bandAround(days: RangeDay[], date: string): RangeDay[] {
  const index = days.findIndex((day) => day.date === date);
  if (index < 0) {
    return [];
  }
  const start = Math.max(0, Math.min(index - BAND_DAYS_BEFORE, days.length - BAND_DAYS));
  return days.slice(start, start + BAND_DAYS);
}

/** Whether the band needs the previous or next year's days to show 28 around this index. */
export function bandNeeds(days: RangeDay[], date: string): { previous: boolean; next: boolean } {
  const index = days.findIndex((day) => day.date === date);
  return {
    previous: index >= 0 && index < BAND_DAYS_BEFORE,
    next: index >= 0 && days.length - index < BAND_DAYS - BAND_DAYS_BEFORE,
  };
}

/** The days after a date in an API list (by position), for "the next closures". */
export function daysAfter(days: RangeDay[], date: string): RangeDay[] {
  const index = days.findIndex((day) => day.date === date);
  return index < 0 ? [] : days.slice(index + 1);
}

// ---- Words ----

/** How a day reads in a sentence: "CHAPS closed Fri 25 Dec 2026: Christmas Day". */
export function dayFact(calendar: Pick<Calendar, "name" | "weekend">, day: RangeDay): string {
  const kind = dayKind(day, calendar.weekend);
  const date = formatDate(day.date);
  return kind === "open"
    ? `${calendar.name} open ${date}`
    : `${calendar.name} ${STATUS_WORDS[kind]} ${date}${day.reason === undefined ? "" : `: ${day.reason}`}`;
}

const STATUS_WORDS: Record<DayKind, string> = {
  open: "open",
  closed: "closed",
  weekend: "closed for the weekend",
  "weekend-holiday": "closed for the weekend and a holiday",
};

/** "A", "A and B", "A, B and C". */
export function listOf(items: string[]): string {
  if (items.length <= 1) {
    return items.join("");
  }
  return `${items.slice(0, -1).join(", ")} and ${items.at(-1)}`;
}

export function publishers(sources: Source[]): string[] {
  return [...new Set(sources.map((source) => source.publisher))];
}

/** Everyone who published an entry in these days, in first-seen order. */
export function calendarPublishers(days: RangeDay[]): string[] {
  return publishers(days.flatMap((day) => day.sources));
}

/**
 * When the calendar's business day opens, as a phrase that follows a date: " at 06:00
 * Europe/London". Empty when the operator publishes no opening time.
 */
export function openingPhrase(calendar: Pick<Calendar, "operating_hours" | "timezone">): string {
  const hours = calendar.operating_hours;
  if (hours === undefined || hours.always_open || hours.opens === undefined) {
    return "";
  }
  return hours.opens_previous_day
    ? `, whose business day opens at ${hours.opens} ${calendar.timezone} the evening before`
    : ` at ${hours.opens} ${calendar.timezone}`;
}

/** One sentence on the calendar's published hours, or undefined when there are none. */
export function hoursSentence(
  calendar: Pick<Calendar, "name" | "operating_hours" | "timezone">,
): string | undefined {
  const hours = calendar.operating_hours;
  if (hours === undefined) {
    return undefined;
  }
  if (hours.always_open) {
    return `${calendar.name} is open around the clock on every day of the year.`;
  }
  if (hours.opens !== undefined && hours.closes !== undefined) {
    return hours.opens_previous_day
      ? `A ${calendar.name} business day runs from ${hours.opens} ${calendar.timezone} on the evening before to ${hours.closes} on the day itself.`
      : `${calendar.name} is open from ${hours.opens} to ${hours.closes} ${calendar.timezone} on business days.`;
  }
  return `${calendar.name} publishes ${plural(hours.cutoffs.length, "cut-off time")} rather than opening hours, all in ${calendar.timezone}.`;
}

export function weekendSentence(calendar: Pick<Calendar, "name" | "weekend">): string {
  return calendar.weekend.length === 0
    ? `${calendar.name} has no weekend: only published closures stop it.`
    : `${calendar.name} is closed every ${listOf(calendar.weekend.map(capitalise))}.`;
}

function capitalise(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

export interface AnswerFacts {
  calendar: Calendar;
  day: RangeDay;
  release: string;
  /** Who published the calendar's entries that year, for days no entry decided. */
  yearPublishers: string[];
}

/**
 * The AnswerLead: one sentence that states the date, calendar, open or closed, why, the next
 * business day with its opening time, the sources and the release. It must make sense quoted
 * alone, so it repeats names instead of saying "it".
 */
export function answerLead({ calendar, day, release, yearPublishers }: AnswerFacts): string {
  const { name } = calendar;
  const date = formatDate(day.date, "long");
  const verdict = day.is_open
    ? `Yes: ${date} is a ${name} business day`
    : `No: ${date} is not a ${name} business day`;
  const reason = day.is_open
    ? ""
    : day.decided_by === "weekend_rule"
      ? `, because ${name} is closed at weekends`
      : `, because ${name} is closed for ${day.reason ?? "a published closure"}`;
  return `${verdict}${reason}${evidence(day, yearPublishers)}; ${nextClause(calendar, day)} (Zeitwide release ${release}).`;
}

function evidence(day: RangeDay, yearPublishers: string[]): string {
  if (day.sources.length > 0) {
    return `, according to ${listOf(publishers(day.sources))}`;
  }
  if (day.is_open && yearPublishers.length > 0) {
    return `, as no closure for it appears in ${listOf(yearPublishers)}`;
  }
  return "";
}

function nextClause(calendar: Calendar, day: RangeDay): string {
  if (day.next_business_day === undefined) {
    return `the next ${calendar.name} business day is beyond Zeitwide's published range`;
  }
  const next = `${formatDate(day.next_business_day, "long")}${openingPhrase(calendar)}`;
  return day.is_open
    ? `the next ${calendar.name} business day is ${next}`
    : `${calendar.name} reopens on ${next}`;
}

/** "Zeitwide (2026). GB-CHAPS on 2026-12-28. Release 2026.10.28.1. https://…" */
export function citation(
  siteUrl: string,
  calendarId: string,
  date: string,
  release: string,
): string {
  const url = absoluteUrl(siteUrl, answerPath(calendarId, date));
  return `Zeitwide (${release.slice(0, 4)}). ${calendarId} on ${date}. Release ${release}. ${url}`;
}

export interface Faq {
  question: string;
  answer: string;
}

/** Up to three questions about the answer, each answered from the same data. */
export function answerFaqs({ calendar, day, release, yearPublishers }: AnswerFacts): Faq[] {
  const { name } = calendar;
  const date = formatDate(day.date, "long");
  const faqs: Faq[] = [];
  if (day.next_business_day !== undefined) {
    const next = `${formatDate(day.next_business_day, "long")}${openingPhrase(calendar)}`;
    faqs.push(
      day.is_open
        ? {
            question: `What is the next ${name} business day after ${date}?`,
            answer: `The next ${name} business day is ${next}.`,
          }
        : {
            question: `When does ${name} reopen after ${date}?`,
            answer: `${name} reopens on ${next}.`,
          },
    );
  }
  const hours = hoursSentence(calendar);
  faqs.push(
    hours === undefined
      ? {
          question: `Which days is ${name} closed?`,
          answer: closedDaysAnswer(calendar, yearPublishers),
        }
      : { question: `What are ${name}'s operating hours?`, answer: hours },
  );
  faqs.push({
    question: "Where does this answer come from?",
    answer: provenance(day, release, yearPublishers),
  });
  return faqs.slice(0, 3);
}

function closedDaysAnswer(calendar: Calendar, yearPublishers: string[]): string {
  const holidays =
    yearPublishers.length === 0
      ? ""
      : ` It also closes on the holidays published by ${listOf(yearPublishers)}.`;
  return `${weekendSentence(calendar)}${holidays}`;
}

function provenance(day: RangeDay, release: string, yearPublishers: string[]): string {
  if (day.sources.length === 0) {
    const checked =
      yearPublishers.length === 0 ? "" : ` and no closure for it in ${listOf(yearPublishers)}`;
    return `The calendar's weekend rule${checked}, in Zeitwide release ${release}.`;
  }
  const read = day.sources
    .map((source) => source.retrieved_at)
    .sort()
    .at(-1);
  const basis =
    day.basis === "projected"
      ? "Zeitwide projected this date from the operator's published rules; the operator has not listed it yet."
      : "The operator published this date.";
  return `${listOf(publishers(day.sources))}, last read ${formatInstant(read)}. ${basis} It is in Zeitwide release ${release}.`;
}

// ---- Years ----

export interface YearSummary {
  year: number;
  closures: RangeDay[];
  /** Closures that fall on a day the calendar would otherwise be open. */
  weekdayClosures: number;
}

export function summarizeYear(
  calendar: Pick<Calendar, "weekend">,
  year: number,
  days: RangeDay[],
): YearSummary {
  const closures = days.filter(isClosure);
  return {
    year,
    closures,
    weekdayClosures: closures.filter((day) => dayKind(day, calendar.weekend) === "closed").length,
  };
}

/** "T2 has 6 closing days in 2027, 3 on weekdays". */
export function yearSentence(calendar: Pick<Calendar, "name">, summary: YearSummary): string {
  const count = summary.closures.length;
  if (count === 0) {
    return `${calendar.name} has no closing days in ${summary.year}`;
  }
  const weekdays =
    summary.weekdayClosures === 1 ? "1 on a weekday" : `${summary.weekdayClosures} on weekdays`;
  return `${calendar.name} has ${plural(count, "closing day")} in ${summary.year}, ${weekdays}`;
}

// ---- Compare ----

const PAIR_SEPARATOR = "-vs-";

export function comparePath(idA: string, idB: string, year: number): string {
  return `/compare/${encodeURIComponent(idA)}${PAIR_SEPARATOR}${encodeURIComponent(idB)}/${year}`;
}

/** The two calendar IDs in "GB-CHAPS-vs-EU-T2", or null when the segment isn't a pair. */
export function parsePair(segment: string): [string, string] | null {
  const parts = segment.split(PAIR_SEPARATOR);
  if (parts.length !== 2 || parts[0] === "" || parts[1] === "" || parts[0] === parts[1]) {
    return null;
  }
  return [parts[0]!, parts[1]!];
}

/** Every unordered pair, each in the one order its URL uses (sorted IDs). */
export function comparePairs(ids: string[]): [string, string][] {
  const sorted = [...new Set(ids)].sort();
  return sorted.flatMap((a, index) => sorted.slice(index + 1).map((b): [string, string] => [a, b]));
}

export function isCanonicalPair(idA: string, idB: string): boolean {
  return idA < idB;
}

export interface CompareRow {
  date: string;
  a: RangeDay | undefined;
  b: RangeDay | undefined;
}

export interface Comparison {
  rows: CompareRow[];
  a: YearSummary;
  b: YearSummary;
  shared: CompareRow[];
}

/** Every date either calendar closes that year, in the API's date order. */
export function compareYear(
  calendarA: Pick<Calendar, "weekend">,
  daysA: RangeDay[],
  calendarB: Pick<Calendar, "weekend">,
  daysB: RangeDay[],
  year: number,
): Comparison {
  const byDateA = new Map(daysA.map((day) => [day.date, day]));
  const byDateB = new Map(daysB.map((day) => [day.date, day]));
  // Both lists hold every covered date in order; the longer one is the timeline.
  const timeline = daysA.length >= daysB.length ? daysA : daysB;
  const rows = timeline
    .map((day) => ({ date: day.date, a: byDateA.get(day.date), b: byDateB.get(day.date) }))
    .filter((row) => isClosedEntry(row.a) || isClosedEntry(row.b));
  return {
    rows,
    a: summarizeYear(calendarA, year, daysA),
    b: summarizeYear(calendarB, year, daysB),
    shared: rows.filter((row) => isClosedEntry(row.a) && isClosedEntry(row.b)),
  };
}

function isClosedEntry(day: RangeDay | undefined): boolean {
  return day !== undefined && isClosure(day);
}

/**
 * "In 2027 CHAPS closes on 8 days and T2 on 6; they share 1 Jan, Good Friday and Easter Monday."
 * A shared closure is named when both calendars give it the same reason, else dated.
 */
export function compareSentence(
  calendarA: Pick<Calendar, "name">,
  calendarB: Pick<Calendar, "name">,
  comparison: Comparison,
): string {
  const countA = comparison.a.closures.length;
  const countB = comparison.b.closures.length;
  const shared = comparison.shared.map((row) =>
    row.a?.reason !== undefined && row.a.reason === row.b?.reason
      ? row.a.reason
      : formatDate(row.date, "dayMonth"),
  );
  const together =
    shared.length === 0 ? "they share no closing days" : `they share ${listOf(shared)}`;
  return `In ${comparison.a.year} ${calendarA.name} closes on ${closingDays(countA)} and ${calendarB.name} on ${countB === 0 ? "none" : countB}; ${together}.`;
}

function closingDays(count: number): string {
  return count === 0 ? "no days" : plural(count, "day");
}

// ---- Sources ----

export interface SourceSummary {
  publisher: string;
  url: string;
  lastRead: string;
  calendars: string[];
}

/** Every source behind the given days, once per URL, with its latest read and its calendars. */
export function collectSources(
  entries: { calendar: Pick<Calendar, "id" | "name">; days: RangeDay[] }[],
): SourceSummary[] {
  const byUrl = new Map<string, SourceSummary>();
  for (const { calendar, days } of entries) {
    for (const source of days.flatMap((day) => day.sources)) {
      const known = byUrl.get(source.url);
      if (known === undefined) {
        byUrl.set(source.url, {
          publisher: source.publisher,
          url: source.url,
          lastRead: source.retrieved_at,
          calendars: [calendar.name],
        });
        continue;
      }
      if (source.retrieved_at > known.lastRead) {
        known.lastRead = source.retrieved_at;
      }
      if (!known.calendars.includes(calendar.name)) {
        known.calendars.push(calendar.name);
      }
    }
  }
  return [...byUrl.values()].sort((x, y) => x.publisher.localeCompare(y.publisher));
}

/** The sources behind one answer, once per URL, with their latest read. */
export function daySources(day: RangeDay): Source[] {
  const byUrl = new Map<string, Source>();
  for (const source of day.sources) {
    const known = byUrl.get(source.url);
    if (known === undefined || source.retrieved_at > known.retrieved_at) {
      byUrl.set(source.url, source);
    }
  }
  return [...byUrl.values()];
}
