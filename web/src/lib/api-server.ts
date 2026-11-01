import "server-only";

import { cacheLife, cacheTag } from "next/cache";

import {
  ApiProblem,
  createZeitwideClient,
  unwrap,
  type CalendarList,
  type DayRange,
  type ZeitwideClient,
} from "@/api/client";

import { env } from "./env";
import { withRetry } from "./retry";

/**
 * Cached reads for the public pages, which prerender. Each calendar's days are cached under the
 * calendar's revision, so a new revision (seen within five minutes on the calendar list, or at
 * once through the revalidation webhook) means fresh answers. Nothing here computes a date.
 */

function api(): ZeitwideClient {
  return createZeitwideClient({
    baseUrl: env.apiUrl,
    ...(env.apiKey === undefined ? {} : { apiKey: env.apiKey }),
  });
}

export const CALENDARS_TAG = "calendars";

export function calendarTag(calendarId: string): string {
  return `calendar:${calendarId}`;
}

export async function getCalendars(): Promise<CalendarList> {
  "use cache";
  cacheLife("calendar");
  cacheTag(CALENDARS_TAG);
  return withRetry(async () => unwrap(await api().GET("/v1/calendars")));
}

/**
 * One calendar's days: the default window (this year and the next 90 days) or one date. The
 * revision is part of the cache key on purpose.
 *
 * @returns null when the calendar doesn't exist
 */
export async function getDays(
  calendarId: string,
  revision: number,
  date?: string,
): Promise<DayRange | null> {
  "use cache";
  cacheLife("calendar");
  cacheTag(CALENDARS_TAG, calendarTag(calendarId));
  void revision;
  const query = date === undefined ? {} : { from: date, to: date };
  try {
    return await withRetry(async () =>
      unwrap(
        await api().GET("/v1/calendars/{id}/days", {
          params: { path: { id: calendarId }, query },
        }),
      ),
    );
  } catch (error) {
    if (
      error instanceof ApiProblem &&
      (error.code === "unknown_calendar" || error.status === 400)
    ) {
      return null;
    }
    throw error;
  }
}
