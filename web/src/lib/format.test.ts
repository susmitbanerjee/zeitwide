import { describe, expect, it } from "vitest";

import { formatDate, formatInstant, isIsoDate, plural, shortHash } from "./format";

describe("isIsoDate", () => {
  it("accepts calendar-shaped dates and rejects anything else", () => {
    expect(isIsoDate("2026-12-28")).toBe(true);
    expect(isIsoDate("2026-13-01")).toBe(false);
    expect(isIsoDate("2026-12-32")).toBe(false);
    expect(isIsoDate("28-12-2026")).toBe(false);
    expect(isIsoDate("2026-12-28/../x")).toBe(false);
  });
});

describe("formatDate", () => {
  it("formats the day as written, whatever the machine's zone", () => {
    expect(formatDate("2026-12-28", "long")).toBe("Monday 28 December 2026");
    expect(formatDate("2026-12-28")).toBe("Mon 28 Dec 2026");
    expect(formatDate("2026-12-28", "plain")).toBe("28 December 2026");
    expect(formatDate("2026-12-28", "short")).toBe("Mon 28 Dec");
    expect(formatDate("2026-12-28", "weekday")).toBe("Mon");
    expect(formatDate("2026-12-28", "day")).toBe("28");
    expect(formatDate("2026-12-28", "month")).toBe("December 2026");
  });

  it("leaves anything that isn't a date alone", () => {
    expect(formatDate("soon")).toBe("soon");
  });
});

describe("formatInstant", () => {
  it("labels instants in UTC", () => {
    expect(formatInstant("2026-10-28T06:10:00Z")).toBe("28 Oct 2026, 06:10 UTC");
  });

  it("says never for a missing instant and passes through junk", () => {
    expect(formatInstant(null)).toBe("never");
    expect(formatInstant(undefined)).toBe("never");
    expect(formatInstant("")).toBe("never");
    expect(formatInstant("not a time")).toBe("not a time");
  });
});

describe("shortHash and plural", () => {
  it("shortens long hashes only", () => {
    expect(shortHash("a".repeat(64))).toBe("aaaaaaaa…aaaaaaaa");
    expect(shortHash("abc")).toBe("abc");
  });

  it("pluralises", () => {
    expect(plural(1, "source")).toBe("1 source");
    expect(plural(2, "source")).toBe("2 sources");
    expect(plural(0, "day", "days")).toBe("0 days");
  });
});
