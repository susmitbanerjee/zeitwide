import { describe, expect, it } from "vitest";

import type { RangeDay } from "@/api/client";

import {
  CHAPS,
  CHAPS_WITH_HOURS,
  DECEMBER,
  FEDNOW,
  FEDWIRE,
  HOLIDAYS,
  T2,
} from "./fixtures.test-data";
import {
  answerFaqs,
  answerLead,
  bandAround,
  bandNeeds,
  buildYears,
  calendarPublishers,
  citation,
  collectSources,
  comparePairs,
  comparePath,
  compareSentence,
  compareYear,
  coveredYears,
  daySources,
  daysAfter,
  hoursSentence,
  isCanonicalPair,
  isYear,
  listOf,
  openingPhrase,
  parsePair,
  summarizeYear,
  weekendSentence,
  yearPath,
  yearRange,
  yearSentence,
} from "./public-pages";

const [christmasEve, christmas, saturday, , boxingDay, tuesday] = DECEMBER.days as [
  RangeDay,
  RangeDay,
  RangeDay,
  RangeDay,
  RangeDay,
  RangeDay,
];
const RELEASE = "2026.10.28.1";

describe("years", () => {
  it("reads year segments and writes their API range", () => {
    expect(isYear("2027")).toBe(true);
    expect(isYear("2026-12-28")).toBe(false);
    expect(yearRange(2027)).toEqual({ from: "2027-01-01", to: "2027-12-31" });
    expect(yearPath("GB-CHAPS", 2027)).toBe("/calendars/GB-CHAPS/2027");
    expect(buildYears("2026-11-02")).toEqual([2026, 2027]);
  });

  it("lists covered years, and the built years for calendars without a limit", () => {
    expect(coveredYears(CHAPS, [2026, 2027])).toEqual([2026, 2027]);
    expect(
      coveredYears({ coverage: { type: "years", from: "2022-01-01", to: "2024-12-31" } }, []),
    ).toEqual([2022, 2023, 2024]);
    expect(coveredYears(FEDNOW, [2026, 2027])).toEqual([2026, 2027]);
    expect(coveredYears({ coverage: { type: "none" } }, [2026])).toEqual([]);
  });

  it("counts closures and those on weekdays", () => {
    const summary = summarizeYear(CHAPS, 2026, DECEMBER.days);
    expect(summary.closures.map((day) => day.date)).toEqual([
      "2026-12-25",
      "2026-12-28",
      "2027-01-01",
    ]);
    expect(summary.weekdayClosures).toBe(3);
    expect(yearSentence(T2, summary)).toBe("T2 has 3 closing days in 2026, 3 on weekdays");
    expect(yearSentence(T2, { year: 2027, closures: [christmas], weekdayClosures: 1 })).toBe(
      "T2 has 1 closing day in 2027, 1 on a weekday",
    );
    expect(yearSentence(FEDNOW, { year: 2027, closures: [], weekdayClosures: 0 })).toBe(
      "FedNow has no closing days in 2027",
    );
  });
});

describe("Zeitband", () => {
  const days = Array.from({ length: 40 }, (_, index): RangeDay => ({
    date: `d${String(index).padStart(2, "0")}`,
    is_open: true,
    decided_by: "weekend_rule",
    sources: [],
  }));

  it("takes 28 days, a week before the date", () => {
    const band = bandAround(days, "d10");
    expect(band).toHaveLength(28);
    expect(band[0]?.date).toBe("d03");
  });

  it("stays inside the list near either end", () => {
    expect(bandAround(days, "d02")[0]?.date).toBe("d00");
    expect(bandAround(days, "d39").at(-1)?.date).toBe("d39");
    expect(bandAround(days, "d39")).toHaveLength(28);
    expect(bandAround(days, "nope")).toEqual([]);
  });

  it("says when the neighbouring year is needed", () => {
    expect(bandNeeds(days, "d03")).toEqual({ previous: true, next: false });
    expect(bandNeeds(days, "d30")).toEqual({ previous: false, next: true });
    expect(bandNeeds(days, "d15")).toEqual({ previous: false, next: false });
  });

  it("lists the days after a date by position", () => {
    expect(daysAfter(days, "d38").map((day) => day.date)).toEqual(["d39"]);
    expect(daysAfter(days, "nope")).toEqual([]);
  });
});

describe("answer words", () => {
  const closed = {
    calendar: CHAPS_WITH_HOURS,
    day: boxingDay,
    release: RELEASE,
    yearPublishers: ["GOV.UK"],
  };

  it("states everything in one quotable sentence when closed", () => {
    expect(answerLead(closed)).toBe(
      "No: Monday 28 December 2026 is not a CHAPS business day, because CHAPS is closed for Boxing Day (substitute day), according to GOV.UK; CHAPS reopens on Tuesday 29 December 2026 at 06:00 Europe/London (Zeitwide release 2026.10.28.1).",
    );
  });

  it("names the weekend rule and the open-day evidence", () => {
    expect(answerLead({ ...closed, day: { ...saturday, next_business_day: "2026-12-29" } })).toBe(
      "No: Saturday 26 December 2026 is not a CHAPS business day, because CHAPS is closed at weekends; CHAPS reopens on Tuesday 29 December 2026 at 06:00 Europe/London (Zeitwide release 2026.10.28.1).",
    );
    expect(answerLead({ ...closed, day: { ...tuesday, next_business_day: "2026-12-30" } })).toBe(
      "Yes: Tuesday 29 December 2026 is a CHAPS business day, as no closure for it appears in GOV.UK; the next CHAPS business day is Wednesday 30 December 2026 at 06:00 Europe/London (Zeitwide release 2026.10.28.1).",
    );
    expect(answerLead({ ...closed, calendar: CHAPS, day: christmasEve, yearPublishers: [] })).toBe(
      "Yes: Thursday 24 December 2026 is a CHAPS business day; the next CHAPS business day is beyond Zeitwide's published range (Zeitwide release 2026.10.28.1).",
    );
  });

  it("phrases opening times for each kind of hours", () => {
    expect(openingPhrase(FEDWIRE)).toBe(
      ", whose business day opens at 21:00 America/New_York the evening before",
    );
    expect(openingPhrase(CHAPS)).toBe("");
    expect(hoursSentence(CHAPS_WITH_HOURS)).toBe(
      "CHAPS is open from 06:00 to 18:00 Europe/London on business days.",
    );
    expect(hoursSentence(FEDWIRE)).toContain("from 21:00 America/New_York on the evening before");
    expect(
      hoursSentence({
        ...FEDNOW,
        operating_hours: { always_open: true, opens_previous_day: false, note: "", cutoffs: [] },
      }),
    ).toBe("FedNow is open around the clock on every day of the year.");
    expect(
      hoursSentence({
        ...CHAPS,
        name: "FedACH",
        operating_hours: {
          always_open: false,
          opens_previous_day: false,
          note: "",
          cutoffs: [{ name: "Window 1", deadline: "10:30" }],
        },
      }),
    ).toBe("FedACH publishes 1 cut-off time rather than opening hours, all in Europe/London.");
    expect(hoursSentence(CHAPS)).toBeUndefined();
  });

  it("writes weekends and lists", () => {
    expect(weekendSentence(CHAPS)).toBe("CHAPS is closed every Saturday and Sunday.");
    expect(weekendSentence(FEDNOW)).toBe("FedNow has no weekend: only published closures stop it.");
    expect(listOf([])).toBe("");
    expect(listOf(["A", "B", "C"])).toBe("A, B and C");
  });

  it("cites the release and the canonical URL", () => {
    expect(citation("https://zeitwide.example", "GB-CHAPS", "2026-12-28", RELEASE)).toBe(
      "Zeitwide (2026). GB-CHAPS on 2026-12-28. Release 2026.10.28.1. https://zeitwide.example/calendars/GB-CHAPS/2026-12-28",
    );
  });

  it("asks at most three questions, all answered from data", () => {
    const faqs = answerFaqs(closed);
    expect(faqs.map((faq) => faq.question)).toEqual([
      "When does CHAPS reopen after Monday 28 December 2026?",
      "What are CHAPS's operating hours?",
      "Where does this answer come from?",
    ]);
    expect(faqs[2]?.answer).toContain("GOV.UK, last read 31 Oct 2026, 06:10 UTC");

    const open = answerFaqs({
      ...closed,
      calendar: CHAPS,
      day: { ...tuesday, next_business_day: "2026-12-30" },
    });
    expect(open.map((faq) => faq.question)).toEqual([
      "What is the next CHAPS business day after Tuesday 29 December 2026?",
      "Which days is CHAPS closed?",
      "Where does this answer come from?",
    ]);
    expect(open[1]?.answer).toContain("holidays published by GOV.UK");
    expect(open[2]?.answer).toContain("weekend rule and no closure for it in GOV.UK");

    const projected = answerFaqs({
      ...closed,
      day: { ...boxingDay, basis: "projected" },
      yearPublishers: [],
    });
    expect(projected[2]?.answer).toContain("projected");
    const noNext = answerFaqs({
      ...closed,
      calendar: CHAPS,
      day: christmasEve,
      yearPublishers: [],
    });
    expect(noNext).toHaveLength(2);
    expect(noNext[0]?.answer).toBe("CHAPS is closed every Saturday and Sunday.");
  });
});

describe("compare", () => {
  it("parses pairs and builds every pair once, in sorted order", () => {
    expect(parsePair("EU-T2-vs-GB-CHAPS")).toEqual(["EU-T2", "GB-CHAPS"]);
    expect(parsePair("GB-CHAPS")).toBeNull();
    expect(parsePair("GB-CHAPS-vs-GB-CHAPS")).toBeNull();
    expect(isCanonicalPair("EU-T2", "GB-CHAPS")).toBe(true);
    expect(isCanonicalPair("GB-CHAPS", "EU-T2")).toBe(false);
    expect(comparePath("EU-T2", "GB-CHAPS", 2027)).toBe("/compare/EU-T2-vs-GB-CHAPS/2027");
    const ids = ["A", "B", "C", "D", "E", "F", "G", "H"];
    expect(comparePairs(ids)).toHaveLength(28);
    expect(comparePairs(["B", "A"])).toEqual([["A", "B"]]);
  });

  it("opens with a one-sentence difference", () => {
    const t2Days = DECEMBER.days.map((day) =>
      day.date === "2026-12-28"
        ? { ...day, is_open: true, decided_by: "weekend_rule" as const }
        : day,
    );
    const comparison = compareYear(CHAPS, DECEMBER.days, T2, t2Days, 2026);
    expect(comparison.rows.map((row) => row.date)).toEqual([
      "2026-12-25",
      "2026-12-28",
      "2027-01-01",
    ]);
    expect(compareSentence(CHAPS, T2, comparison)).toBe(
      "In 2026 CHAPS closes on 3 days and T2 on 2; they share Christmas Day and New Year's Day.",
    );
    const renamed = compareYear(
      CHAPS,
      DECEMBER.days,
      T2,
      t2Days.map((day) => (day.reason === "Christmas Day" ? { ...day, reason: "Christmas" } : day)),
      2026,
    );
    expect(compareSentence(CHAPS, T2, renamed)).toContain("they share 25 Dec and New Year's Day");
    const none = compareYear(FEDNOW, [tuesday], T2, [tuesday], 2026);
    expect(compareSentence(FEDNOW, T2, none)).toBe(
      "In 2026 FedNow closes on no days and T2 on none; they share no closing days.",
    );
  });
});

describe("sources", () => {
  const read = (at: string): RangeDay => ({
    ...christmas,
    sources: [{ ...christmas.sources[0]!, retrieved_at: at }],
  });

  it("merges sources by URL with their latest read and calendars", () => {
    const sources = collectSources([
      { calendar: CHAPS, days: [read("2026-10-01T00:00:00Z"), read("2026-10-31T00:00:00Z")] },
      { calendar: HOLIDAYS, days: [read("2026-10-02T00:00:00Z")] },
    ]);
    expect(sources).toEqual([
      {
        publisher: "GOV.UK",
        url: "https://www.gov.uk/bank-holidays",
        lastRead: "2026-10-31T00:00:00Z",
        calendars: ["CHAPS", "England and Wales"],
      },
    ]);
    expect(calendarPublishers(DECEMBER.days)).toEqual(["GOV.UK"]);
  });

  it("keeps one source per URL for a day", () => {
    const day = {
      ...christmas,
      sources: [...read("2026-10-01T00:00:00Z").sources, ...christmas.sources],
    };
    expect(daySources(day)).toHaveLength(1);
    expect(daySources(day)[0]?.retrieved_at).toBe("2026-10-31T06:10:00Z");
  });
});
