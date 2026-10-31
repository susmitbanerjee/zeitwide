// Fixture data for the fake Zeitwide API. Holiday dates follow the operators' published lists for
// 2026 and 2027; the 2022 State Funeral entry exists so time-travel tests have a real change.

export const TODAY = "2026-10-31";
export const NOW = "2026-10-31T09:00:00Z";
export const RELEASE = "2026.10.31.1";
export const RELEASE_AS_OF = "2026-10-31T06:00:00Z";

const SOURCES = {
  frbservices: {
    publisher: "Federal Reserve Financial Services",
    url: "https://www.frbservices.org/about/holiday-schedules",
  },
  "frb-k8": {
    publisher: "Federal Reserve Board · K.8",
    url: "https://www.federalreserve.gov/aboutthefed/k8.htm",
  },
  ecb: {
    publisher: "European Central Bank · TARGET closing days",
    url: "https://www.ecb.europa.eu/paym/target/t2/html/index.en.html",
  },
  "gov-uk": { publisher: "GOV.UK · UK bank holidays", url: "https://www.gov.uk/bank-holidays" },
  boe: {
    publisher: "Bank of England · CHAPS and bank holidays",
    url: "https://www.bankofengland.co.uk/payment-and-settlement/chaps",
  },
  opm: {
    publisher: "U.S. Office of Personnel Management",
    url: "https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/",
  },
  "de-law": { publisher: "Federal holiday law (Germany)", url: "https://www.bmi.bund.de/" },
};

const FED = {
  "2026-01-01": "New Year's Day",
  "2026-01-19": "Martin Luther King Jr. Day",
  "2026-02-16": "Washington's Birthday",
  "2026-05-25": "Memorial Day",
  "2026-06-19": "Juneteenth",
  "2026-07-04": "Independence Day, on a Saturday",
  "2026-09-07": "Labor Day",
  "2026-10-12": "Columbus Day",
  "2026-11-11": "Veterans Day",
  "2026-11-26": "Thanksgiving Day",
  "2026-12-25": "Christmas Day",
  "2027-01-01": "New Year's Day",
  "2027-01-18": "Martin Luther King Jr. Day",
  "2027-02-15": "Washington's Birthday",
  "2027-05-31": "Memorial Day",
  "2027-06-19": "Juneteenth, on a Saturday",
  "2027-07-05": "Independence Day, observed",
  "2027-09-06": "Labor Day",
  "2027-10-11": "Columbus Day",
  "2027-11-11": "Veterans Day",
  "2027-11-25": "Thanksgiving Day",
  "2027-12-25": "Christmas Day, on a Saturday",
};
const T2 = {
  "2026-01-01": "New Year's Day",
  "2026-04-03": "Good Friday",
  "2026-04-06": "Easter Monday",
  "2026-05-01": "Labour Day",
  "2026-12-25": "Christmas Day",
  "2026-12-26": "Christmas holiday, on a Saturday",
  "2027-01-01": "New Year's Day",
  "2027-03-26": "Good Friday",
  "2027-03-29": "Easter Monday",
  "2027-05-01": "Labour Day, on a Saturday",
  "2027-12-25": "Christmas Day, on a Saturday",
  "2027-12-26": "Christmas holiday, on a Sunday",
};
const UK = {
  "2022-09-19": "State Funeral of Queen Elizabeth II",
  "2026-01-01": "New Year's Day",
  "2026-04-03": "Good Friday",
  "2026-04-06": "Easter Monday",
  "2026-05-04": "Early May bank holiday",
  "2026-05-25": "Spring bank holiday",
  "2026-08-31": "Summer bank holiday",
  "2026-12-25": "Christmas Day",
  "2026-12-28": "Boxing Day (substitute day)",
  "2027-01-01": "New Year's Day",
  "2027-03-26": "Good Friday",
  "2027-03-29": "Easter Monday",
  "2027-05-03": "Early May bank holiday",
  "2027-05-31": "Spring bank holiday",
  "2027-08-30": "Summer bank holiday",
  "2027-12-27": "Christmas Day (substitute day)",
  "2027-12-28": "Boxing Day (substitute day)",
};
const DE = {
  "2026-01-01": "Neujahr",
  "2026-04-03": "Karfreitag",
  "2026-04-06": "Ostermontag",
  "2026-05-01": "Tag der Arbeit",
  "2026-05-14": "Christi Himmelfahrt",
  "2026-05-25": "Pfingstmontag",
  "2026-10-03": "Tag der Deutschen Einheit, on a Saturday",
  "2026-12-25": "Erster Weihnachtstag",
  "2026-12-26": "Zweiter Weihnachtstag, on a Saturday",
  "2027-01-01": "Neujahr",
  "2027-03-26": "Karfreitag",
  "2027-03-29": "Ostermontag",
  "2027-05-01": "Tag der Arbeit, on a Saturday",
  "2027-05-06": "Christi Himmelfahrt",
  "2027-05-17": "Pfingstmontag",
  "2027-10-03": "Tag der Deutschen Einheit, on a Sunday",
  "2027-12-25": "Erster Weihnachtstag, on a Saturday",
  "2027-12-26": "Zweiter Weihnachtstag, on a Sunday",
};

const WEEKEND = ["saturday", "sunday"];

export const CALENDARS = [
  {
    id: "US-FEDWIRE",
    name: "Fedwire Funds",
    kind: "rail",
    timezone: "America/New_York",
    holidays: FED,
    sources: ["frbservices", "frb-k8"],
    years: [2026, 2027],
  },
  {
    id: "US-FEDACH",
    name: "FedACH",
    kind: "rail",
    timezone: "America/New_York",
    holidays: FED,
    sources: ["frbservices"],
    years: [2026, 2027],
  },
  {
    id: "US-FEDNOW",
    name: "FedNow",
    kind: "rail",
    timezone: "America/New_York",
    holidays: {},
    sources: [],
    years: null,
    weekend: [],
  },
  {
    id: "EU-T2",
    name: "T2",
    kind: "rail",
    timezone: "Europe/Berlin",
    holidays: T2,
    sources: ["ecb"],
    years: [2026, 2027],
  },
  {
    id: "GB-CHAPS",
    name: "CHAPS",
    kind: "rail",
    timezone: "Europe/London",
    holidays: UK,
    sources: ["gov-uk", "boe"],
    years: [2022, 2027],
  },
  {
    id: "US-FEDERAL",
    name: "United States, federal",
    kind: "public",
    timezone: "America/New_York",
    holidays: FED,
    sources: ["opm"],
    years: [2026, 2027],
  },
  {
    id: "GB-EAW",
    name: "England and Wales",
    kind: "public",
    timezone: "Europe/London",
    holidays: UK,
    sources: ["gov-uk"],
    years: [2022, 2027],
  },
  {
    id: "DE",
    name: "Germany, nationwide",
    kind: "public",
    timezone: "Europe/Berlin",
    holidays: DE,
    sources: ["de-law"],
    years: [2026, 2027],
  },
].map((calendar) => ({ weekend: WEEKEND, ...calendar }));

/** When each entry was recorded; the 2022 funeral was proclaimed on 10 September 2022. */
export function recordedFrom(date) {
  return date === "2022-09-19" ? "2022-09-10T12:00:00Z" : "2025-12-01T06:00:00Z";
}

export function sourcesFor(calendar, date) {
  return calendar.sources.map((key, index) => ({
    ...SOURCES[key],
    retrieved_at: "2026-10-31T06:10:00Z",
    snapshot_sha256: hashLike(`${key}:${date}:${index}`),
  }));
}

export function hashLike(text) {
  let hash = "";
  let seed = 2166136261;
  for (let round = 0; round < 8; round++) {
    for (const char of `${round}:${text}`) {
      seed = Math.imul(seed ^ char.charCodeAt(0), 16777619) >>> 0;
    }
    hash += seed.toString(16).padStart(8, "0");
  }
  return hash;
}

export const CHANGES = [
  {
    id: 1,
    type: "closure_added",
    calendar_id: "GB-CHAPS",
    entry_date: "2022-09-19",
    before: { is_open: true, reason: "Business day" },
    after: { is_open: false, reason: "State Funeral of Queen Elizabeth II", basis: "published" },
    notice_days: 9,
    published_at: "2022-09-10T12:00:00Z",
  },
  {
    id: 2,
    type: "closure_added",
    calendar_id: "GB-CHAPS",
    entry_date: "2026-12-28",
    before: null,
    after: { is_open: false, reason: "Boxing Day (substitute day)", basis: "published" },
    notice_days: 392,
    published_at: "2025-12-01T06:00:00Z",
  },
  {
    id: 3,
    type: "basis_changed",
    calendar_id: "EU-T2",
    entry_date: "2027-03-26",
    before: { is_open: false, reason: "Good Friday", basis: "projected" },
    after: { is_open: false, reason: "Good Friday", basis: "published" },
    notice_days: 160,
    published_at: "2026-10-17T08:00:00Z",
  },
];

export function initialAdminState() {
  return {
    candidates: [
      {
        id: 41,
        calendar_id: "US-FEDWIRE",
        entry_date: "2026-11-10",
        before: null,
        after: { is_open: false, reason: "Special closure announced by the Board" },
        corroboration: "single_source",
        source_count: 1,
        status: "pending",
        created_at: "2026-10-30T06:20:00Z",
        snapshots: [
          snapshot("frbservices", "primary", false, "Special closure announced by the Board"),
        ],
      },
      {
        id: 42,
        calendar_id: "GB-CHAPS",
        entry_date: "2027-05-10",
        before: null,
        after: { is_open: false, reason: "Coronation anniversary bank holiday" },
        corroboration: "corroborated",
        source_count: 2,
        status: "pending",
        created_at: "2026-10-29T06:15:00Z",
        snapshots: [
          snapshot("gov-uk", "primary", false, "Coronation anniversary bank holiday"),
          snapshot("boe", "corroborating", false, "CHAPS closed for the bank holiday"),
        ],
      },
    ],
    sources: [
      sourceHealth("frbservices", "primary", "daily", "2026-10-31T06:10:00Z", 0, null),
      sourceHealth("frb-k8", "corroborating", "daily", "2026-10-31T06:10:00Z", 0, null),
      sourceHealth("ecb", "primary", "daily", "2026-10-31T06:11:00Z", 0, null),
      sourceHealth("gov-uk", "primary", "daily", "2026-10-31T06:10:00Z", 0, null),
      sourceHealth(
        "boe",
        "corroborating",
        "daily",
        "2026-10-31T06:12:00Z",
        2,
        "Read timed out after 30 s",
      ),
    ],
    releases: [
      release(RELEASE, RELEASE_AS_OF),
      release("2026.10.30.1", "2026-10-30T06:00:00Z"),
      release("2026.10.17.1", "2026-10-17T08:00:00Z"),
    ],
  };
}

function snapshot(key, role, isOpen, reason) {
  return {
    source: key,
    ...SOURCES[key],
    role,
    fetched_at: "2026-10-30T06:10:00Z",
    snapshot_sha256: hashLike(`snapshot:${key}`),
    http_status: 200,
    content_type: "text/html",
    byte_size: 48_213,
    is_open: isOpen,
    reason,
    parser_version: "v3",
  };
}

function sourceHealth(key, role, schedule, checked, failures, error) {
  return {
    source: key,
    ...SOURCES[key],
    role,
    schedule,
    enabled: true,
    health: failures > 0 ? "failing" : "healthy",
    consecutive_failures: failures,
    last_checked_at: checked,
    last_success_at: failures > 0 ? "2026-10-30T06:12:00Z" : checked,
    last_snapshot_at: "2026-10-30T06:12:00Z",
    last_snapshot_sha256: hashLike(`snapshot:${key}`),
    last_error: error,
    suspended_at: null,
  };
}

function release(id, asOf) {
  return {
    id,
    as_of: asOf,
    created_at: asOf,
    export_sha256: hashLike(`release:${id}`),
    calendar_revisions: Object.fromEntries(
      CALENDARS.map((calendar, index) => [calendar.id, 10 + index]),
    ),
  };
}
