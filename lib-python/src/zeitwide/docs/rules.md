---
title: How Zeitwide decides a business day
description: The rules every Zeitwide answer follows, in the API, the MCP tools and the open library, for one calendar or several.
updated: 2026-11-02
---

# How Zeitwide decides a business day

The Java API, the hosted MCP server and the open Python library all follow these rules,
and a shared conformance suite checks that they give the same answers for the same
dataset release.

## One calendar, one date

1. **Coverage.** A calendar that closes on holidays covers 1 January of its first year to
   31 December of the last year in which it has a recorded entry. A date outside that is
   `out_of_range`: Zeitwide never guesses beyond what the sources published. FedNow closes
   on no holiday, so it has no limit.
2. **Entry.** If the date has an entry (a published or projected closure, or an opening on
   an otherwise closed day), the entry decides.
3. **Weekend rule.** With no entry, the date is open unless its weekday is one of the
   calendar's weekend days.

## Several calendars

Calendars combine with `all_open` (the default: every calendar is open) or `any_open` (at
least one is). If any of them is out of range, so is the answer.

## Operations

- `is_business_day(date)`: the rules above.
- `next_business_day(date)`: the first business day after the date, never the date itself.
- `add_business_days(date, n)`: moves `n` business days, backwards when `n` is negative.
  The start date doesn't count, and `n = 0` returns it. Every day passed over that isn't a
  business day is listed in `skipped`, in the order met.

## Where the data comes from

Zeitwide monitors each operator's official pages. A change is published only after two
independent sources agree and a person reviews it. Each release is versioned
(`YYYY.MM.DD.N`), and every answer names the release it came from, so it can be replayed
as of any past moment.

- `published` dates are listed by the operator.
- `projected` dates are worked out by Zeitwide from the operator's published rules, before
  the operator lists them.
