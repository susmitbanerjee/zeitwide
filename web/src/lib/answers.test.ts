import { describe, expect, it } from "vitest";

import {
  answerPageParams,
  calendarLabel,
  closures,
  dayKind,
  daysAround,
  findCalendar,
  findDay,
  groupCalendars,
  nextClosure,
  weekdayName,
} from "./answers";
import { CHAPS, DECEMBER, FEDNOW, LIST } from "./fixtures.test-data";

describe("lookups in API lists", () => {
  it("finds calendars and days, or nothing", () => {
    expect(findCalendar(LIST, "GB-CHAPS")?.name).toBe("CHAPS");
    expect(findCalendar(LIST, "XX")).toBeUndefined();
    expect(findDay(DECEMBER, "2026-12-28")?.reason).toBe("Boxing Day (substitute day)");
    expect(findDay(DECEMBER, "2030-01-01")).toBeUndefined();
    expect(findDay(null, "2026-12-28")).toBeUndefined();
  });

  it("slices the days around a date without leaving the list", () => {
    expect(daysAround(DECEMBER, "2026-12-28", 2, 1).map((d) => d.date)).toEqual([
      "2026-12-26",
      "2026-12-27",
      "2026-12-28",
      "2026-12-29",
    ]);
    expect(daysAround(DECEMBER, "2026-12-24", 7, 0).map((d) => d.date)).toEqual(["2026-12-24"]);
    expect(daysAround(DECEMBER, "1999-01-01")).toEqual([]);
  });

  it("finds closures, and the next one after a date", () => {
    expect(closures(DECEMBER).map((d) => d.date)).toEqual([
      "2026-12-25",
      "2026-12-28",
      "2027-01-01",
    ]);
    expect(nextClosure(DECEMBER, "2026-12-28")?.date).toBe("2027-01-01");
    expect(nextClosure(DECEMBER, "2027-01-01")).toBeUndefined();
  });
});

describe("dayKind", () => {
  it("tells open, closed and weekend days apart", () => {
    expect(dayKind(DECEMBER.days[0]!, CHAPS.weekend)).toBe("open");
    expect(dayKind(DECEMBER.days[1]!, CHAPS.weekend)).toBe("closed");
    expect(dayKind(DECEMBER.days[2]!, CHAPS.weekend)).toBe("weekend");
  });

  it("marks a holiday that falls on one of the calendar's weekend days", () => {
    const saturday = { ...DECEMBER.days[1]!, date: "2026-12-26" };
    expect(dayKind(saturday, CHAPS.weekend)).toBe("weekend-holiday");
    expect(dayKind(saturday, FEDNOW.weekend)).toBe("closed");
  });

  it("names weekdays the way the API does", () => {
    expect(weekdayName("2026-12-26")).toBe("saturday");
    expect(weekdayName("2026-12-28")).toBe("monday");
  });
});

describe("calendars", () => {
  it("groups rails before public holidays and drops empty groups", () => {
    const groups = groupCalendars(LIST.calendars);
    expect(groups.map((g) => g.label)).toEqual(["Payment rails", "Public holidays"]);
    expect(groups[0]?.calendars.map((c) => c.id)).toEqual(["GB-CHAPS", "US-FEDNOW"]);
    expect(calendarLabel(CHAPS)).toBe("CHAPS");
  });

  it("lists one static page per listed day", () => {
    const params = answerPageParams([
      { calendarId: "GB-CHAPS", range: DECEMBER },
      { calendarId: "XX", range: null },
    ]);
    expect(params).toHaveLength(DECEMBER.days.length);
    expect(params[0]).toEqual({ id: "GB-CHAPS", date: "2026-12-24" });
  });
});
