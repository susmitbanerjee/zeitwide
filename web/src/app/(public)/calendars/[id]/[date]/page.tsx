import type { Metadata } from "next";

import { publicPageMetadata, renderPublicPage } from "@/components/public/render";
import { upcomingDateParams, yearParams } from "@/lib/public-data";

// Every page here is complete static HTML: listed ones at build, others on first request.
export const ensureStatic = "navigation";

/**
 * One segment, two templates: /calendars/GB-CHAPS/2027 is a year of closures and
 * /calendars/GB-CHAPS/2026-12-28 is one answer. Year pages are built for every covered year and
 * answer pages for today and the next 90 days.
 */
export async function generateStaticParams(): Promise<{ id: string; date: string }[]> {
  const [dates, years] = await Promise.all([upcomingDateParams(), yearParams()]);
  return [...years.map(({ id, year }) => ({ id, date: String(year) })), ...dates];
}

async function path(params: PageProps<"/calendars/[id]/[date]">["params"]): Promise<string> {
  const { id, date } = await params;
  return `/calendars/${id}/${date}`;
}

export async function generateMetadata({
  params,
}: PageProps<"/calendars/[id]/[date]">): Promise<Metadata> {
  return publicPageMetadata(await path(params));
}

export default async function CalendarDatePage({ params }: PageProps<"/calendars/[id]/[date]">) {
  return renderPublicPage(await path(params));
}
