# Conformance suite

Golden cases that **both** the Java API and the Python library must pass. A dataset
release cannot ship if either side fails a case.

- One case per file in `cases/`, named `<calendar>-<short-description>.json`.
- Each file matches [`case.schema.json`](case.schema.json): an input request, the
  dataset release it was written against, the expected date, and the days skipped.
- Cases are data. Never encode business-day rules in a test runner; the runners in
  `api/` and `lib-python/` only load these files and compare answers.

## What the answers mean

- **A business day** is a day with an entry saying it is open, or a day with no entry
  that isn't one of the calendar's weekend days. Entries are the release's data; FedNow,
  for example, has no weekend days and no closures.
- **`combine`**: `all_open` (the default) is a business day only when every listed
  calendar is open; `any_open` when at least one is.
- **`add_business_days`** moves `days` business days from `date`, backwards when
  negative. The start day itself doesn't count, and 0 returns `date`. `skipped` lists
  the non-business days passed over, in the order they were met.
- **Releases:** a case written against release R applies to R and every later release.
  If a later release breaks a case, the facts changed: update the case in the same pull
  request, with the reason.
- **`as_of`** cases need the API's bitemporal history; a release only holds what was
  recorded at its own `as_of`, so the library runner reports them as unsupported.
