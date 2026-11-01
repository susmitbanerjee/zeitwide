import { describe, expect, it } from "vitest";

import type { Calendar, DayRange, RangeDay } from "@/api/client";

import {
  bandRails,
  bandSummary,
  checkData,
  choiceDates,
  pickRails,
  settleHeadline,
  upcomingClosures,
} from "./landing";

const calendar = (id: string, name: string): Calendar => ({
  id,
  name,
  kind: "rail",
  timezone: "UTC",
  weekend: ["saturday", "sunday"],
  closes_on_holidays: true,
  revision: 1,
  coverage: { type: "unlimited" },
});

const open = (date: string, next = "2026-11-02"): RangeDay => ({
  date,
  is_open: true,
  decided_by: "weekend_rule",
  sources: [],
  next_business_day: next,
});

const holiday = (date: string, reason: string): RangeDay => ({
  date,
  is_open: false,
  decided_by: "entry",
  reason,
  basis: "published",
  sources: [
    { publisher: "GOV.UK", url: "https://gov.uk", retrieved_at: "", snapshot_sha256: "a" },
    { publisher: "GOV.UK", url: "https://gov.uk/2", retrieved_at: "", snapshot_sha256: "b" },
  ],
  next_business_day: "2026-11-03",
});

const range = (id: string, days: RangeDay[]): DayRange =>
  ({ calendar_id: id, today: "2026-10-31", days }) as unknown as DayRange;

const chaps = calendar("GB-CHAPS", "CHAPS");
const fedwire = calendar("US-FEDWIRE", "Fedwire Funds");
const days = ["2026-10-31", "2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04"];

const rails = [
  {
    calendar: chaps,
    range: range(
      "GB-CHAPS",
      days.map((d) => open(d)),
    ),
  },
  {
    calendar: fedwire,
    range: range(
      "US-FEDWIRE",
      days.map((d) => (d === "2026-11-03" ? holiday(d, "Election Day") : open(d))),
    ),
  },
];

describe("pickRails", () => {
  it("keeps landing rails in landing order and drops the rest", () => {
    const other = { calendar: calendar("DE", "Germany"), range: range("DE", []) };
    const picked = pickRails([other, rails[1]!, rails[0]!]);
    expect(picked.map((entry) => entry.calendar.id)).toEqual(["GB-CHAPS", "US-FEDWIRE"]);
  });
});

describe("choiceDates", () => {
  it("starts with today and adds closures from any rail in date order", () => {
    expect(choiceDates(rails)).toEqual(["2026-10-31", "2026-11-03"]);
  });

  it("stops after the requested number of closures", () => {
    const many = [
      {
        calendar: chaps,
        range: range("GB-CHAPS", [
          open("2026-10-31"),
          holiday("2026-11-01", "A"),
          holiday("2026-11-02", "B"),
          holiday("2026-11-03", "C"),
        ]),
      },
    ];
    expect(choiceDates(many, 2)).toEqual(["2026-10-31", "2026-11-01", "2026-11-02"]);
  });

  it("is empty without rails", () => {
    expect(choiceDates([])).toEqual([]);
  });
});

describe("checkData", () => {
  it("answers each rail for each choice date from the API's own fields", () => {
    const data = checkData(rails, "2026.10.31.1");
    expect(data?.today).toBe("2026-10-31");
    expect(data?.rails[1]?.answers["2026-11-03"]).toEqual({
      open: false,
      weekend: false,
      reason: "Election Day",
      next: "2026-11-03",
      publishers: ["GOV.UK"],
    });
    expect(data?.rails[0]?.answers["2026-11-03"]?.open).toBe(true);
  });

  it("is null without rails", () => {
    expect(checkData([], "r")).toBeNull();
  });
});

describe("bandRails and bandSummary", () => {
  it("slices from today and names closures that change something", () => {
    const band = bandRails(rails, 3);
    expect(band[0]?.days.map((d) => d.date)).toEqual(["2026-10-31", "2026-11-01", "2026-11-02"]);
    const wide = bandRails(rails, 5);
    expect(
      bandSummary(
        wide,
        (date) => date,
        () => false,
      ),
    ).toBe("CHAPS has no holiday closures. Fedwire Funds closed 2026-11-03.");
    expect(
      bandSummary(
        wide,
        (date) => date,
        () => true,
      ),
    ).toContain("Fedwire Funds has no holiday closures.");
  });
});

describe("upcomingClosures", () => {
  it("lists which rails close and which stay open, after today", () => {
    expect(upcomingClosures(rails)).toEqual([
      {
        date: "2026-11-03",
        closed: [{ id: "US-FEDWIRE", name: "Fedwire Funds", reason: "Election Day" }],
        stillOpen: ["CHAPS"],
      },
    ]);
  });

  it("stops at the limit and is empty without rails", () => {
    expect(upcomingClosures(rails, 0)).toEqual([]);
    expect(upcomingClosures([])).toEqual([]);
  });
});

describe("settleHeadline", () => {
  it("says how many rails settle on the API's today", () => {
    expect(settleHeadline(rails)).toBe("All 2 rails settle today.");
    const shut = [
      rails[0]!,
      { calendar: fedwire, range: range("US-FEDWIRE", [holiday("2026-10-31", "Holiday")]) },
    ];
    expect(settleHeadline(shut)).toBe("1 of 2 rails settle today.");
    expect(settleHeadline([shut[1]!])).toBe("No rail settles today.");
    expect(settleHeadline([rails[0]!])).toBe("The rail settles today.");
  });
});
