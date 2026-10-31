import "server-only";

import type { Calendar, DayRange, RangeDay } from "@/api/client";

import { findCalendar, findDay } from "./answers";
import { getCalendars, getDays } from "./api-server";
import { isIsoDate } from "./format";

export interface AnswerPageData {
  calendar: Calendar;
  day: RangeDay;
  /** The calendar's default window when the date is inside it, for the strip and next closure. */
  window: DayRange | null;
  others: { calendar: Calendar; day: RangeDay | undefined }[];
  datasetRelease: string;
}

/** The day from the calendar's cached window, or a one-day read for dates outside it. */
async function dayOn(
  calendar: Calendar,
  date: string,
): Promise<{ day?: RangeDay; window: DayRange | null }> {
  const window = await getDays(calendar.id, calendar.revision);
  const inWindow = findDay(window, date);
  if (inWindow !== undefined) {
    return { day: inWindow, window };
  }
  const single = findDay(await getDays(calendar.id, calendar.revision, date), date);
  return single === undefined ? { window: null } : { day: single, window: null };
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
  const { day, window } = await dayOn(calendar, date);
  if (day === undefined) {
    return null;
  }
  const others = await Promise.all(
    list.calendars
      .filter((other) => other.id !== calendar.id)
      .map(async (other) => ({ calendar: other, day: (await dayOn(other, date)).day })),
  );
  return { calendar, day, window, others, datasetRelease: list.dataset_release };
}
