/**
 * Display formatting only. Every date passed in comes from the API as an ISO string; nothing here
 * adds, subtracts or compares dates.
 */

const ISO_DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

/** Whether a URL segment looks like an ISO date. The API decides whether the date exists. */
export function isIsoDate(value: string): boolean {
  return ISO_DATE.test(value);
}

const formats = {
  // Monday 28 December 2026
  long: new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  }),
  // Mon 28 Dec 2026
  medium: new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  }),
  // 28 December 2026
  plain: new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    day: "numeric",
    month: "long",
    year: "numeric",
  }),
  // Mon 28 Dec
  short: new Intl.DateTimeFormat("en-GB", {
    timeZone: "UTC",
    weekday: "short",
    day: "numeric",
    month: "short",
  }),
  // 1 Jan
  dayMonth: new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", day: "numeric", month: "short" }),
  weekday: new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "short" }),
  day: new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", day: "numeric" }),
  month: new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", month: "long", year: "numeric" }),
} as const;

export type DateStyle = keyof typeof formats;

/** Formats an ISO date (YYYY-MM-DD) as written, read as a calendar day with no time zone. */
export function formatDate(isoDate: string, style: DateStyle = "medium"): string {
  if (!isIsoDate(isoDate)) {
    return isoDate;
  }
  // Parts joined by spaces, so ICU versions that add a comma after the weekday read the same.
  return formats[style]
    .formatToParts(new Date(`${isoDate}T00:00:00Z`))
    .filter((part) => part.type !== "literal")
    .map((part) => part.value)
    .join(" ");
}

const instantFormat = new Intl.DateTimeFormat("en-GB", {
  timeZone: "UTC",
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** Formats an API timestamp in UTC, labelled as such: 28 Oct 2026, 06:10 UTC. */
export function formatInstant(instant: string | null | undefined): string {
  if (instant === null || instant === undefined || instant === "") {
    return "never";
  }
  const parsed = new Date(instant);
  if (Number.isNaN(parsed.getTime())) {
    return instant;
  }
  return `${instantFormat.format(parsed)} UTC`;
}

/** The first and last eight hex characters, for showing a SHA-256 without overwhelming a row. */
export function shortHash(hash: string): string {
  return hash.length <= 20 ? hash : `${hash.slice(0, 8)}…${hash.slice(-8)}`;
}

export function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`;
}
