import type { Calendar, CalendarList, DayRange, RangeDay } from "@/api/client";

/** Shared API-shaped fixtures for unit tests. */

export const CHAPS: Calendar = {
  id: "GB-CHAPS",
  name: "CHAPS",
  kind: "rail",
  timezone: "Europe/London",
  weekend: ["saturday", "sunday"],
  closes_on_holidays: true,
  revision: 41,
  coverage: { type: "years", from: "2026-01-01", to: "2027-12-31" },
};

export const FEDNOW: Calendar = {
  ...CHAPS,
  id: "US-FEDNOW",
  name: "FedNow",
  weekend: [],
  coverage: { type: "unlimited" },
};

export const HOLIDAYS: Calendar = {
  ...CHAPS,
  id: "GB-EAW",
  name: "England and Wales",
  kind: "public",
};

const RECEIPT = {
  id: "rcpt_0123456789abcdef0123456789abcdef",
  payload: {
    request: {},
    answer: {},
    dataset_release: "2026.10.31.1",
    calendar_revisions: {},
    issued_at: "2026-10-31T00:00:00Z",
  },
  payload_hash: "0".repeat(64),
  key_id: "zw-2026-10",
  signature: "A".repeat(86),
};

export const LIST: CalendarList = {
  calendars: [HOLIDAYS, CHAPS, FEDNOW],
  as_of: "2026-10-31T00:00:00Z",
  dataset_release: "2026.10.31.1",
  calendar_revisions: {},
  receipt: RECEIPT,
};

function day(date: string, overrides: Partial<RangeDay> = {}): RangeDay {
  return { date, is_open: true, decided_by: "weekend_rule", sources: [], ...overrides };
}

const closed = (date: string, reason: string): RangeDay =>
  day(date, {
    is_open: false,
    decided_by: "entry",
    reason,
    basis: "published",
    sources: [
      {
        publisher: "GOV.UK",
        url: "https://www.gov.uk/bank-holidays",
        retrieved_at: "2026-10-31T06:10:00Z",
        snapshot_sha256: "c".repeat(64),
      },
    ],
    next_business_day: "2026-12-29",
    previous_business_day: "2026-12-24",
  });

export const DECEMBER: DayRange = {
  calendar_id: "GB-CHAPS",
  today: "2026-10-31",
  from: "2026-12-24",
  to: "2027-01-04",
  days: [
    day("2026-12-24"),
    closed("2026-12-25", "Christmas Day"),
    day("2026-12-26", { is_open: false }),
    day("2026-12-27", { is_open: false }),
    closed("2026-12-28", "Boxing Day (substitute day)"),
    day("2026-12-29"),
    day("2026-12-30"),
    day("2026-12-31"),
    closed("2027-01-01", "New Year's Day"),
    day("2027-01-02", { is_open: false }),
    day("2027-01-03", { is_open: false }),
    day("2027-01-04"),
  ],
  as_of: "2026-10-31T00:00:00Z",
  dataset_release: "2026.10.31.1",
  calendar_revisions: { "GB-CHAPS": 41 },
  receipt: RECEIPT,
};
