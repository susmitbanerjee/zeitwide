import type { Calendar, RangeDay } from "@/api/client";

import { dayKind, groupCalendars, type DayKind } from "./answers";
import { formatDate, formatInstant, plural } from "./format";
import type { AnswerPageData, CalendarPageData, CalendarYear, YearPageData } from "./public-data";
import {
  answerFaqs,
  answerLead,
  calendarPath,
  citation,
  compareSentence,
  comparePath,
  dayFact,
  daySources,
  hoursSentence,
  listOf,
  publishers,
  weekendSentence,
  yearOf,
  yearPath,
  yearSentence,
  type Comparison,
  type Faq,
  type SourceSummary,
} from "./public-pages";
import { absoluteUrl, answerJsonLd, answerPath, answerQuestion } from "./seo";

/**
 * One model per public page type, built from API data. React renders it as HTML and toMarkdown
 * renders it as Markdown, so both always state the same facts. Builders only look things up and
 * write sentences; the API decides every date.
 */

export interface PageLink {
  text: string;
  /** Site-relative ("/calendars/GB-CHAPS") or absolute for external sources. */
  href: string;
}

export interface Cell {
  text: string;
  href?: string;
  /** How the day is drawn, for cells that state one calendar's day. */
  kind?: DayKind;
}

/** A table whose first column is the row header. */
export interface TableBlock {
  id: string;
  heading: string;
  caption: string;
  columns: string[];
  rows: Cell[][];
  /** Shown as the only row when there are none. */
  empty: string;
}

export interface SourceItem {
  publisher: string;
  url: string;
  /** "Read 31 Oct 2026, 06:10 UTC" */
  read: string;
  /** What the source backs, when a page lists sources for several calendars. */
  detail?: string;
}

export interface Crumb {
  label: string;
  href?: string;
}

export interface LinkList {
  id: string;
  heading: string;
  links: PageLink[];
}

interface PageModelBase {
  /** The canonical HTML URL; the Markdown lives at the same path plus ".md". */
  url: string;
  title: string;
  lead: string;
  crumbs: Crumb[];
  tables: TableBlock[];
  lists: LinkList[];
  /** Notes printed after the tables, such as a calendar's own hours note. */
  notes: string[];
  faqs: Faq[];
  sources: SourceItem[];
  /** Said where there are no sources, such as "decided by the weekend rule". */
  noSources?: string;
  release?: string;
  cite?: string;
}

export interface BandDay {
  date: string;
  /** "Mon 21 Dec 2026" */
  label: string;
  initial: string;
  dayNumber: string;
  fact: string;
  href: string;
  kind: DayKind;
  selected: boolean;
}

export interface AnswerPageModel extends PageModelBase {
  type: "answer";
  open: boolean;
  /** Next and previous business day links, under the lead. */
  neighbours: PageLink[];
  band: { heading: string; caption: string; calendarName: string; days: BandDay[] };
  evidence: PageLink;
  api: { curl: string; json: string };
  jsonLd: unknown;
  /** The facts the Markdown and HTML must agree on. */
  facts: {
    date: string;
    calendarId: string;
    open: boolean;
    reason: string | null;
    nextBusinessDay: string | null;
  };
}

export interface YearPageModel extends PageModelBase {
  type: "year";
}

export interface CalendarPageModel extends PageModelBase {
  type: "calendar";
  today: PageLink | null;
}

export interface ComparePageModel extends PageModelBase {
  type: "compare";
}

export interface ListPageModel extends PageModelBase {
  type: "calendars" | "sources" | "pricing";
  /** Plain bullet points (pricing). */
  bullets: string[];
  bulletsHeading?: string;
}

export type PageModel =
  AnswerPageModel | YearPageModel | CalendarPageModel | ComparePageModel | ListPageModel;

const BASE = { crumbs: [], tables: [], lists: [], notes: [], faqs: [], sources: [] };

function sourceItems(
  sources: { publisher: string; url: string; retrieved_at: string }[],
): SourceItem[] {
  return sources.map((source) => ({
    publisher: source.publisher,
    url: source.url,
    read: `Read ${formatInstant(source.retrieved_at)}`,
  }));
}

function summarySources(sources: SourceSummary[], withCalendars: boolean): SourceItem[] {
  return sources.map((source) => ({
    publisher: source.publisher,
    url: source.url,
    read: `Read ${formatInstant(source.lastRead)}`,
    ...(withCalendars ? { detail: `Backs ${listOf(source.calendars)}` } : {}),
  }));
}

function statusCell(calendar: Calendar, day: RangeDay): Cell {
  return {
    text: dayFact(calendar, day),
    href: answerPath(calendar.id, day.date),
    kind: dayKind(day, calendar.weekend),
  };
}

function reopensCell(calendar: Calendar, day: RangeDay): Cell {
  return day.next_business_day === undefined
    ? { text: "Beyond published dates" }
    : {
        text: `${calendar.name} reopens ${formatDate(day.next_business_day)}`,
        href: answerPath(calendar.id, day.next_business_day),
      };
}

// ---- /calendars/{id}/{yyyy-mm-dd} ----

export function answerPageModel(data: AnswerPageData, siteUrl: string): AnswerPageModel {
  const { calendar, day, band, others, release, yearPublishers } = data;
  const facts = { calendar, day, release, yearPublishers };
  const lead = answerLead(facts);
  const faqs = answerFaqs(facts);
  const shortDate = formatDate(day.date);
  const year = yearOf(day.date);
  const neighbours: PageLink[] = [];
  if (day.next_business_day !== undefined) {
    neighbours.push({
      text: `${day.is_open ? `Next ${calendar.name} business day is` : `${calendar.name} reopens`} ${formatDate(day.next_business_day)}`,
      href: answerPath(calendar.id, day.next_business_day),
    });
  }
  if (day.previous_business_day !== undefined) {
    neighbours.push({
      text: `Previous ${calendar.name} business day was ${formatDate(day.previous_business_day)}`,
      href: answerPath(calendar.id, day.previous_business_day),
    });
  }
  const first = band[0];
  const last = band.at(-1);
  return {
    ...BASE,
    type: "answer",
    url: absoluteUrl(siteUrl, answerPath(calendar.id, day.date)),
    title: answerQuestion(calendar, day.date),
    lead,
    open: day.is_open,
    crumbs: [
      { href: "/calendars", label: "Calendars" },
      { href: calendarPath(calendar.id), label: calendar.name },
      { href: yearPath(calendar.id, year), label: String(year) },
      { label: formatDate(day.date, "plain") },
    ],
    neighbours,
    band: {
      heading: `28 days of ${calendar.name} around ${shortDate}`,
      caption:
        first === undefined || last === undefined
          ? calendar.name
          : `${calendar.name}, ${formatDate(first.date)} to ${formatDate(last.date)}`,
      calendarName: calendar.name,
      days: band.map((bandDay) => ({
        date: bandDay.date,
        label: formatDate(bandDay.date),
        initial: formatDate(bandDay.date, "weekday").slice(0, 1),
        dayNumber: formatDate(bandDay.date, "day"),
        fact: dayFact(calendar, bandDay),
        href: answerPath(calendar.id, bandDay.date),
        kind: dayKind(bandDay, calendar.weekend),
        selected: bandDay.date === day.date,
      })),
    },
    tables: [
      {
        id: "others",
        heading: `Other calendars on ${shortDate}`,
        caption: `${formatDate(day.date, "long")} on ${plural(others.length, "other calendar")}`,
        columns: ["Calendar", "Status", "Why"],
        rows: others.map(({ calendar: other, day: otherDay }) => [
          { text: `${other.name} (${other.id})` },
          otherDay === undefined ? { text: "No data" } : statusCell(other, otherDay),
          {
            text:
              otherDay === undefined
                ? "Outside this calendar's sources"
                : (otherDay.reason ?? (otherDay.is_open ? "Business day" : "Weekend")),
          },
        ]),
        empty: "No other calendars.",
      },
    ],
    faqs,
    sources: sourceItems(daySources(day)),
    ...(day.sources.length === 0
      ? {
          noSources: `No entry decides this date, so ${calendar.name}'s weekend rule does. ${weekendSentence(calendar)}`,
        }
      : {}),
    release,
    cite: citation(siteUrl, calendar.id, day.date, release),
    evidence: {
      text: `Evidence and signed receipt for ${calendar.name} on ${shortDate}`,
      href: `/app/evidence/${calendar.id}/${day.date}`,
    },
    api: {
      curl: `curl https://api.zeitwide.com/v1/calendars/${calendar.id}/days/${day.date}`,
      json: JSON.stringify(
        {
          date: day.date,
          is_business_day: day.is_open,
          reason: day.reason,
          dataset_release: release,
        },
        null,
        2,
      ),
    },
    jsonLd: answerJsonLd(siteUrl, calendar, day, lead, faqs),
    facts: {
      date: day.date,
      calendarId: calendar.id,
      open: day.is_open,
      reason: day.reason ?? null,
      nextBusinessDay: day.next_business_day ?? null,
    },
  };
}

// ---- /calendars/{id}/{year} ----

export function yearPageModel(data: YearPageData, siteUrl: string): YearPageModel {
  const { calendar, summary, release, neighbours, comparisons } = data;
  const { year } = summary;
  const lists: LinkList[] = [];
  if (neighbours.length > 0) {
    lists.push({
      id: "years",
      heading: "Other years",
      links: neighbours.map((neighbour) => ({
        text: yearSentence(calendar, neighbour),
        href: yearPath(calendar.id, neighbour.year),
      })),
    });
  }
  if (comparisons.length > 0) {
    lists.push({
      id: "compare",
      heading: `${calendar.name} against other calendars in ${year}`,
      links: comparisons.map(({ calendar: other, comparison }) => {
        const [idA, idB] = [calendar.id, other.id].sort() as [string, string];
        return {
          text: `${calendar.name} and ${other.name} share ${plural(comparison.shared.length, "closing day")} in ${year}`,
          href: comparePath(idA, idB, year),
        };
      }),
    });
  }
  return {
    ...BASE,
    type: "year",
    url: absoluteUrl(siteUrl, yearPath(calendar.id, year)),
    title: `${calendar.name} closures in ${year}`,
    lead: `${yearSentence(calendar, summary)}. ${weekendSentence(calendar)} Every date below is from Zeitwide release ${release}.`,
    crumbs: [
      { href: "/calendars", label: "Calendars" },
      { href: calendarPath(calendar.id), label: calendar.name },
      { label: String(year) },
    ],
    tables: [
      {
        id: "closures",
        heading: "Every closure",
        caption: `${calendar.name} closures in ${year}, with the official source for each`,
        columns: ["Date", "Reason", "Basis", "Reopens", "Sources"],
        rows: summary.closures.map((day) => [
          statusCell(calendar, day),
          { text: day.reason ?? "" },
          { text: day.basis === "projected" ? "Projected" : "Published" },
          reopensCell(calendar, day),
          { text: listOf(publishers(day.sources)) },
        ]),
        empty: `${calendar.name} has no published closures in ${year}.`,
      },
    ],
    lists,
    // Every source behind this year's closures, once per URL.
    sources: sourceItems([
      ...new Map(summary.closures.flatMap((day) => day.sources).map((s) => [s.url, s])).values(),
    ]),
    release,
  };
}

// ---- /calendars/{id} ----

const KIND_LABELS = {
  rail: "a payment rail",
  public: "a public holiday calendar",
  exchange: "an exchange calendar",
} as const;

export function calendarPageModel(data: CalendarPageData, siteUrl: string): CalendarPageModel {
  const { calendar, today, todayAnswer, release, nextClosures, years, sources } = data;
  const hours = calendar.operating_hours;
  const tables: TableBlock[] = [];
  if (hours !== undefined && hours.cutoffs.length > 0) {
    tables.push({
      id: "hours",
      heading: "Hours",
      caption: `${calendar.name} cut-off times, ${calendar.timezone}`,
      columns: ["Payment", "Cut-off"],
      rows: hours.cutoffs.map((cutoff) => [{ text: cutoff.name }, { text: cutoff.deadline }]),
      empty: "",
    });
  }
  tables.push({
    id: "next",
    heading: "Next closures",
    caption: `The next ${nextClosures.length === 1 ? "closure" : `${nextClosures.length} closures`} on ${calendar.name} after ${formatDate(today, "plain")}`,
    columns: ["Date", "Reason", "Reopens"],
    rows: nextClosures.map((day) => [
      statusCell(calendar, day),
      { text: day.reason ?? "" },
      reopensCell(calendar, day),
    ]),
    empty: `${calendar.name} has no published closures ahead.`,
  });
  return {
    ...BASE,
    type: "calendar",
    url: absoluteUrl(siteUrl, calendarPath(calendar.id)),
    title: `${calendar.name} business days and closures`,
    lead: [
      `${calendar.name} (${calendar.id}) is ${KIND_LABELS[calendar.kind]} on ${calendar.timezone} time.`,
      weekendSentence(calendar),
      hoursSentence(calendar),
    ]
      .filter((part) => part !== undefined)
      .join(" "),
    crumbs: [{ href: "/calendars", label: "Calendars" }, { label: calendar.name }],
    today:
      todayAnswer === undefined
        ? null
        : {
            text: `${calendar.name} is ${todayAnswer.is_open ? "open" : "closed"} today, ${formatDate(today, "long")}`,
            href: answerPath(calendar.id, today),
          },
    notes: [
      hours === undefined
        ? `${calendar.name} is a list of closing days; its publisher sets no opening hours.`
        : hours.note,
    ],
    tables,
    lists: [
      {
        id: "years",
        heading: "Closures by year",
        links: years.map((summary) => ({
          text: yearSentence(calendar, summary),
          href: yearPath(calendar.id, summary.year),
        })),
      },
    ],
    sources: summarySources(sources, false),
    noSources: `${calendar.name} closes on no holiday, so no source lists its dates.`,
    release,
  };
}

// ---- /compare/{idA}-vs-{idB}/{year} ----

export interface CompareModelData {
  a: Calendar;
  b: Calendar;
  year: number;
  comparison: Comparison;
  release: string;
}

export function comparePageModel(data: CompareModelData, siteUrl: string): ComparePageModel {
  const { a, b, year, comparison, release } = data;
  const dayCell = (calendar: Calendar, day: RangeDay | undefined): Cell =>
    day === undefined ? { text: `Outside ${calendar.name}'s sources` } : statusCell(calendar, day);
  return {
    ...BASE,
    type: "compare",
    url: absoluteUrl(siteUrl, comparePath(a.id, b.id, year)),
    title: `${a.name} vs ${b.name}: closures in ${year}`,
    lead: compareSentence(a, b, comparison),
    crumbs: [
      { href: "/calendars", label: "Calendars" },
      { label: `${a.name} and ${b.name}, ${year}` },
    ],
    tables: [
      {
        id: "side-by-side",
        heading: "Side by side",
        caption: `Every day ${a.name} or ${b.name} is closed in ${year}`,
        columns: ["Date", a.name, b.name],
        rows: comparison.rows.map((row) => [
          { text: formatDate(row.date) },
          dayCell(a, row.a),
          dayCell(b, row.b),
        ]),
        empty: `Neither calendar has a published closure in ${year}.`,
      },
    ],
    lists: [
      {
        id: "years",
        heading: "Each calendar's full year",
        links: [
          { text: yearSentence(a, comparison.a), href: yearPath(a.id, year) },
          { text: yearSentence(b, comparison.b), href: yearPath(b.id, year) },
        ],
      },
    ],
    notes: [`From Zeitwide release ${release}.`],
    release,
  };
}

// ---- /calendars ----

export function calendarsPageModel(
  entries: CalendarYear[],
  release: string,
  siteUrl: string,
): ListPageModel {
  return {
    ...BASE,
    type: "calendars",
    url: absoluteUrl(siteUrl, "/calendars"),
    title: "Calendars",
    lead: `Zeitwide covers ${plural(entries.length, "calendar")}; dates outside a calendar's coverage are never guessed.`,
    tables: groupCalendars(entries.map((entry) => entry.calendar)).map((group) => ({
      id: group.label.toLowerCase().replace(/\s+/g, "-"),
      heading: group.label,
      caption: group.label,
      columns: ["Calendar", "Time zone", "Weekend", "Covered"],
      rows: group.calendars.map((calendar) => {
        const summary = entries.find((entry) => entry.calendar.id === calendar.id)?.summary;
        return [
          summary === undefined
            ? { text: `${calendar.name} (${calendar.id})` }
            : { text: `${yearSentence(calendar, summary)}`, href: calendarPath(calendar.id) },
          { text: calendar.timezone },
          {
            text:
              calendar.weekend.length === 0 ? "None, open every day" : calendar.weekend.join(", "),
          },
          { text: coverageText(calendar) },
        ];
      }),
      empty: "",
    })),
    bullets: [],
    release,
  };
}

function coverageText(calendar: Calendar): string {
  const { coverage } = calendar;
  if (coverage.type === "unlimited") {
    return "Every date";
  }
  if (coverage.type === "none") {
    return "Nothing yet";
  }
  return `${formatDate(coverage.from ?? "", "plain")} to ${formatDate(coverage.to ?? "", "plain")}`;
}

// ---- /sources ----

export function sourcesPageModel(
  sources: SourceSummary[],
  release: string,
  years: number[],
  siteUrl: string,
): ListPageModel {
  return {
    ...BASE,
    type: "sources",
    url: absoluteUrl(siteUrl, "/sources"),
    title: "Sources",
    lead: `Zeitwide answers from ${plural(sources.length, "official source")}, listed here for closures in ${listOf(years.map(String))}. A change is published only after two independent sources agree and a reviewer signs it off; this list is from release ${release}.`,
    sources: summarySources(sources, true),
    bullets: [],
    release,
  };
}

// ---- /pricing ----

export function pricingPageModel(siteUrl: string): ListPageModel {
  return {
    ...BASE,
    type: "pricing",
    url: absoluteUrl(siteUrl, "/pricing"),
    title: "Pricing",
    lead: "Zeitwide is free during the beta, with every feature included on every account.",
    bulletsHeading: "Free",
    bullets: [
      "Every calendar: Fedwire, FedACH, FedNow, T2, CHAPS and public holidays",
      "REST API, hosted MCP server and change webhooks",
      "A signed receipt with every answer, and answers as of any past moment",
      "10,000 API calls a day per key; 60 an hour without a key",
    ],
    notes: [
      "The dataset is published under CC BY-NC 4.0, and the Python library with its local MCP server is MIT licensed.",
    ],
    lists: [
      {
        id: "keys",
        heading: "Open data and code",
        links: [{ text: "API keys are free from the developer quickstart", href: "/docs" }],
      },
    ],
  };
}
