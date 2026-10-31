import type { Calendar, RangeDay } from "@/api/client";

import { formatDate } from "./format";

/** The question an answer page answers, also its title and its JSON-LD question. */
export function answerQuestion(calendar: Pick<Calendar, "name">, date: string): string {
  return `Is ${formatDate(date, "long")} a ${calendar.name} business day?`;
}

/** One sentence that answers the question, in the words the page uses. */
export function answerSentence(calendar: Pick<Calendar, "name">, day: RangeDay): string {
  if (day.is_open) {
    return `Yes. ${calendar.name} is open for settlement on ${formatDate(day.date, "long")}.`;
  }
  if (day.decided_by === "weekend_rule") {
    return `No. ${formatDate(day.date, "long")} is a weekend day, and ${calendar.name} does not settle at weekends.`;
  }
  return `No. ${calendar.name} is closed on ${formatDate(day.date, "long")}: ${day.reason ?? "a closure"}.`;
}

export function answerDescription(calendar: Pick<Calendar, "name">, day: RangeDay): string {
  const neighbours = [
    day.next_business_day === undefined
      ? undefined
      : `Next business day: ${formatDate(day.next_business_day)}.`,
    day.previous_business_day === undefined
      ? undefined
      : `Previous: ${formatDate(day.previous_business_day)}.`,
  ].filter((part) => part !== undefined);
  return [answerSentence(calendar, day), ...neighbours, "Backed by official sources."].join(" ");
}

export function answerPath(calendarId: string, date: string): string {
  return `/calendars/${encodeURIComponent(calendarId)}/${date}`;
}

export function absoluteUrl(siteUrl: string, path: string): string {
  return new URL(path, siteUrl).toString();
}

type JsonLd = Record<string, unknown>;

export function answerJsonLd(
  siteUrl: string,
  calendar: Pick<Calendar, "id" | "name">,
  day: RangeDay,
): JsonLd[] {
  const url = absoluteUrl(siteUrl, answerPath(calendar.id, day.date));
  return [
    {
      "@context": "https://schema.org",
      "@type": "FAQPage",
      url,
      mainEntity: [
        {
          "@type": "Question",
          name: answerQuestion(calendar, day.date),
          acceptedAnswer: { "@type": "Answer", text: answerDescription(calendar, day) },
        },
      ],
    },
    {
      "@context": "https://schema.org",
      "@type": "BreadcrumbList",
      itemListElement: [
        {
          "@type": "ListItem",
          position: 1,
          name: "Calendars",
          item: absoluteUrl(siteUrl, "/calendars"),
        },
        {
          "@type": "ListItem",
          position: 2,
          name: calendar.name,
          item: absoluteUrl(siteUrl, `/calendars/${encodeURIComponent(calendar.id)}`),
        },
        { "@type": "ListItem", position: 3, name: formatDate(day.date, "plain"), item: url },
      ],
    },
  ];
}

/**
 * JSON for a `<script type="application/ld+json">`. Escaping `<`, `>`, `&` and the two line
 * separators means no API string can close the script element or start markup.
 */
export function serializeJsonLd(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");
}
