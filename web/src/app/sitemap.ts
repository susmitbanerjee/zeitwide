import type { MetadataRoute } from "next";

import { answerPageParams } from "@/lib/answers";
import { getCalendars, getDays } from "@/lib/api-server";
import { env } from "@/lib/env";
import { absoluteUrl, answerPath } from "@/lib/seo";

/** Every public page: home, docs, each calendar and each answer page in the default window. */
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const { calendars } = await getCalendars();
  const ranges = await Promise.all(
    calendars.map(async (calendar) => ({
      calendarId: calendar.id,
      range: await getDays(calendar.id, calendar.revision),
    })),
  );
  const fixed = ["/", "/calendars", "/docs", "/docs/api"].map((path) => ({
    url: absoluteUrl(env.siteUrl, path),
  }));
  const calendarPages = calendars.map((calendar) => ({
    url: absoluteUrl(env.siteUrl, `/calendars/${calendar.id}`),
  }));
  const answers = answerPageParams(ranges).map(({ id, date }) => ({
    url: absoluteUrl(env.siteUrl, answerPath(id, date)),
  }));
  return [...fixed, ...calendarPages, ...answers];
}
