# Dataset releases

Exported dataset releases (for example `2026.10.28.1`), written by the pipeline's
release builder and mirrored to the public data repository. Published under
**CC BY-NC 4.0**. Releases are immutable once cut: never edit a release in place.

## Format

A release is one JSON file matching [`schema/release.schema.json`](schema/release.schema.json)
(`schema_version` 2). It holds each calendar's rules (weekend days, whether it closes on
holidays, observance and operating hours) and **every version** of every entry that
SettleCal had recorded at the release's `as_of`. Each version keeps when it was believed
(`recorded_from` up to `recorded_to`, null while current) and the source snapshots
behind it, so the open library can answer "as of" questions offline, just like the API.

Release IDs are `YYYY.MM.DD.N`. The pipeline numbers each day's releases from 1; `N = 0`
is reserved for the golden dataset in [`conformance/`](../conformance/), which is not a
release.

Schema version 1 had only the versions current at `as_of`, without `recorded_to`. The
library still reads it, as if every entry were current.

## How a release answers

These rules are the contract. The Java engine (`api/.../answers/domain`) and the Python
engine (`lib-python/src/settlecal/engine.py`) both implement exactly this, and the
conformance suite checks both against the same cases.

**One calendar, one date `D`, as of instant `T`** (default: the release's `as_of`):

1. **Coverage.** A calendar that closes on holidays covers 1 January of the first year
   to 31 December of the last year in which it has an entry recorded at `T`. A date
   outside that is `out_of_range`: the engine never guesses beyond what sources
   published. A calendar with `closes_on_holidays: false` (FedNow) has no limit.
2. **Entry.** The version of `D`'s entry recorded at `T` (`recorded_from <= T <
   recorded_to`) decides: open or closed.
3. **Weekend rule.** With no entry, `D` is open unless its weekday is in `weekend`.

**Several calendars** combine with `all_open` (the default: every calendar is open) or
`any_open` (at least one is). If any of them is out of range, so is the answer.

**Operations:**

- `is_business_day(D)`: the rules above.
- `next_business_day(D)`: the first business day after `D` (never `D` itself).
- `add_business_days(D, N)`: moves `N` business days from `D`, backwards when `N` is
  negative. `D` itself doesn't count, and `N = 0` returns `D`. Every day passed over
  that isn't a business day is reported in `skipped`, in the order met.

Every date an operation looks at, including `D`, must be in range. Unknown calendar IDs
are `unknown_calendar`.

Observance rules are data for the pipeline, which uses them to project dates.
Entries already hold the observed dates, so engines never apply observance.
