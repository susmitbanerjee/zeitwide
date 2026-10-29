# Conformance suite

Golden cases that **both** engines must pass: the Java one in `api/` (and so the REST
API and the hosted MCP server) and the Python one in the open library. A dataset release
cannot ship if either side fails a case.

- One case per file in `cases/`, named after its `id`.
- Each file matches [`case.schema.json`](case.schema.json): an input request, the
  dataset release it was written against, and the expected answer: `is_business_day`, a
  `date` with the days `skipped`, or an `error` (`out_of_range`, `unknown_calendar`).
- Cases are data. Never encode business-day rules in a test runner; the runners only
  load these files, ask their engine and compare.
- What the answers mean is defined once, in [`dataset/README.md`](../dataset/README.md).

## The golden dataset

[`dataset.json`](dataset.json) is in the release format, but it isn't a release (its ID
ends in `.0`). The pipeline's test suite builds it from the real source snapshots in
`pipeline/tests/fixtures/`, recorded in the order they were fetched, and fails if the
committed file differs. That includes two Internet Archive captures of GOV.UK from just
before and just after the State Funeral bank holiday of 19 September 2022 was announced,
so the as-of cases are backed by real evidence. To rebuild it after changing the
fixtures, from `pipeline/`:

```bash
SETTLECAL_WRITE_GOLDEN_DATASET=1 uv run python -m pytest tests/test_golden_dataset.py
```

## Where the cases run

| Runner | Against | Rule |
| --- | --- | --- |
| `api/` `ConformanceTest` (in `./gradlew check`) | the golden dataset | every case must pass |
| `api/` `JdbcDatasetSourceTest` | the golden dataset written to Postgres and read back | every case must pass |
| `api/` `AnswersHttpTest` | the same, through the REST API over HTTP | every case must pass |
| `lib-python/` `test_conformance_suite.py` | the golden dataset | every case must pass |
| `lib-python/` `test_conformance_suite.py` | each file in `dataset/releases/` | cases that apply must pass |
| `pipeline/` end-to-end test | a release built from the fixtures | cases that apply must pass |

**Which cases apply to a real release:** a case written against release R applies to R
and every later release. If a later release breaks a case, the facts changed: update the
case in the same pull request, with the reason. A real release covers fewer years and
less history than the golden dataset, so when it answers `out_of_range` to a case that
expects an answer, the case is reported as not applicable rather than failed. It never
guesses, so it can't be wrong there.
