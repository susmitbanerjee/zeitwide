# zeitwide-pipeline

Python workers that **write** calendar data: source monitors, parsers, diffing,
corroboration, the review CLI and the release builder. Runs as Cloud Run Jobs.

```bash
uv sync
uv run zeitwide-pipeline health
```

## Commands

The `zeitwide` command (also installed as `zeitwide-pipeline`, the name the Cloud Run
jobs use):

| Command | What it does |
| --- | --- |
| `run-due` | Cloud Run job, every 15 minutes: checks each source that is due (below). |
| `daily` | Cloud Run job, daily: publishes approved candidates, cuts a release if a revision changed, runs `pg_dump`, then the 30-day clean-up. |
| `review list` | Pending candidates, urgent first (under 14 days' notice). |
| `review show ID` | One candidate: before and after, and every source snapshot behind it. |
| `review approve ID --reason … --reviewer …` | Approves and publishes in one transaction. |
| `review reject ID --reason … --reviewer …` | Rejects; nothing is published. |
| `seed [--year 2026 --year 2027]` | Publishes each calendar's first versions from the sources, without review. |
| `resume-source ID` | Checks a suspended source again. |
| `dry-run [ID …]`, `save-fixture ID NAME` | Fetch and parse live sources; save a real snapshot for golden tests. |

`--reviewer` (or `ZEITWIDE_REVIEWER`) is recorded with every decision. The CLI connects
with the pipeline's database login, so anyone who can run it can approve. The web review
screen will record the reviewer's sign-in subject instead.

## Review and publish

**Approving a candidate publishes it in one transaction:**

1. Lock the candidate row.
2. Bump the calendar revision.
3. Call `publish_entry_version`, which closes the current version and inserts the new
   one.
4. Write an `entry_source` row for each supporting snapshot.
5. Write a `change_event` (`closure_added`, `closure_removed`, `closure_changed` or
   `basis_changed`, with its notice days) and its `outbox` row.

`change_event.candidate_id` is unique, so publishing the same candidate twice is a
no-op. Candidates the web screen approves (status `approved`) are published by `daily`.

**Basis:** a change is `published` when a source run by the calendar's operator
supports it (`operator_sources` in the calendar catalog). Otherwise it is `projected`:
GB-CHAPS dates come from GOV.UK, and the Bank of England publishes only the rule. When
the operator later confirms a projected date, the diff proposes a `basis_changed`
candidate.

## Releases

`daily` cuts `YYYY.MM.DD.N` when any calendar's revision differs from the latest
release. A release is every entry as recorded at one instant (`as_of`), in the format
of [`dataset/schema/release.schema.json`](../dataset/schema/release.schema.json). It is
rendered with sorted keys, so the same `as_of` always gives the same bytes.

**Distribution:**
- `dataset_release` records the release's SHA-256.
- The file goes to the snapshots bucket as `releases/<id>.json`.
- A pull request adds `releases/<id>.json` to `ZEITWIDE_DATASET_REPOSITORY`.
- If an upload or pull request fails, the next `daily` rebuilds the latest release,
  checks it against the recorded SHA-256 and finishes the job.

The end-to-end test runs every release through the conformance suite with the open
library.

### The open dataset

`daily` also publishes the release where models, agents and developers already look. All of
it is generated from the release export (`src/zeitwide_pipeline/distribution/`); nothing is
edited by hand.

- **GitHub `zeitwide-data`** (`ZEITWIDE_OPEN_DATA_REPOSITORY`, `GITHUB_DATA_TOKEN`): one commit
  per release on the default branch, tagged with the release ID. It holds `data/<ID>.csv` and
  `data/<ID>.json` per calendar (`date`, `weekday`, `open`, `reason`, `basis`, `sources`,
  `revision`), `calendars.csv`, a README that opens with a one-paragraph summary and a table
  of calendars with closure counts, `CITATION.cff`, `LICENSE` (CC BY-NC 4.0) and
  `CHANGELOG.md` (the change events, derived from the release's entry history).
- **Hugging Face `zeitwide/payment-rail-calendars`** (`ZEITWIDE_HUB_DATASET`, `HF_TOKEN`): the
  same tables as Parquet, one configuration per calendar, with a dataset card (YAML metadata,
  summary, sourcing, known limits, how to cite). The card is validated with
  `huggingface_hub` before every push.
- **PyPI** `zeitwide` ships its own README and bundled release; tag `lib-python-v<version>`
  (`.github/workflows/publish-lib-python.yml`).

Every file links each calendar to its canonical page on the site. Each bundle carries
`MANIFEST.json` and `SHA256SUMS`; the manifest holds the release export's SHA-256, so every
file traces to the release the database recorded. It also holds a `data_digest` over the CSV
tables, which say nothing about the release itself. A target whose repository already holds
that digest is skipped (`unchanged`), a release that is already tagged is left alone, and a
commit that landed without its tag only gets the tag, so the daily retry is safe.

```bash
# Build everything into ./out/github and ./out/huggingface, re-read it, check the checksums
# and the dataset card. Needs no database, token or network.
uv run zeitwide-pipeline open-data dry-run --release ../dataset/releases/2026.10.29.1.json   --out out --site-url https://zeitwide.com --api-url https://api.zeitwide.com   --expect-sha256 <checksum recorded for the release>
# Add --validate-online to also ask the Hub's own validator about the card.

# Push a release by hand (what daily does); needs the tokens and ZEITWIDE_SITE_URL.
uv run zeitwide-pipeline open-data publish --release ../dataset/releases/2026.10.29.1.json
```

## Seed

`seed` fetches every source once, stores the snapshots and observations, and publishes
every closure in the chosen years as revision 1 of its calendar, with basis as above.
Dates the sources disagree on are left out and listed. Running it again adds nothing for
dates that already have an entry.

## How a check works

`zeitwide-pipeline run-due` is the Cloud Run job's entry point. It syncs every source
module into the `source` table, defers one Procrastinate job per source that is due
(Postgres-backed queue, one queued and one running job per source), works the queue
until it is empty, and exits. Nothing runs between invocations: Cloud Scheduler starts
the job every 15 minutes.

Each `check_source` job runs four steps, each with its own OpenTelemetry span:

1. **Monitor** (`fetch.py`, `storage.py`): `httpx` with 5 s connect and 30 s read
   timeouts, and `tenacity` retries with jittered backoff for timeouts, 429 and 5xx.
   It sends the `ZeitwideBot` User-Agent with a contact URL, obeys `robots.txt`,
   waits at least 1 s between requests to the same host, and sends
   `If-None-Match`/`If-Modified-Since`. It follows only same-host https redirects and
   caps the size. New bytes go to Cloud Storage as `sha256/<hex>` (create-only), then
   into `source_snapshot`. A 304, or bytes that were already parsed, end the check
   there.
2. **Parse**: `parser.parse(bytes, meta) -> list[Observation]`, a pure function. An
   unexpected layout or zero rows raises `ParseError`.
3. **Diff** (`diff.plan_diff`, pure): compares open/closed with the current published
   entry and open candidates. A difference opens a `candidate_change`; agreeing
   observations from any source are linked to it. Unique constraints make re-running a
   snapshot a no-op.
4. **Corroborate**: a candidate is `corroborated` (ready for review) once observations
   from two distinct sources support it; otherwise it stays `single_source` (flagged).

Steps 3 and 4 and the success record are one transaction. A failed check (fetch or parse)
increments `source_state.consecutive_failures`. After 3 in a row the source is
suspended: it isn't checked again until someone runs
`zeitwide-pipeline resume-source <id>`.

**Signals:** JSON logs from structlog with `severity`, `trace_id`, `source_id` and
`snapshot_sha256`. Metrics:
- `zeitwide.source.last_success_timestamp` (gauge): alert when it is older than twice
  the source's schedule.
- `zeitwide.source.parse_failures` (counter).
- `zeitwide.source.suspended` (gauge): alert when it is 1.

## Adding a source

Add one module to `src/zeitwide_pipeline/sources/catalog/` that defines `SOURCE` (see
`sources/base.py` for the protocol). Then add golden tests: a stored real snapshot in
`tests/fixtures/<source id>/` and its expected observations as JSON. When the page
changes layout, add the new snapshot and keep the old ones. No other file changes;
the registry finds the module.

## Configuration

| Variable | Meaning |
| --- | --- |
| `PGHOST`, `PGUSER`, `PGPASSWORD`, `PGSSLMODE`, … | libpq connection (Cloud Run sets these), or `ZEITWIDE_DATABASE_CONNINFO` |
| `ZEITWIDE_SNAPSHOT_BUCKET` | Snapshot bucket |
| `ZEITWIDE_BOT_CONTACT_URL` | https page about the crawler, sent in the User-Agent |
| `ZEITWIDE_DB_POOL_MAX_SIZE` | Connections in total (2–5; default 2) |
| `ZEITWIDE_GCS_ENDPOINT` | fake-gcs-server URL locally; unset on GCP |
| `ZEITWIDE_BACKUP_BUCKET`, `ZEITWIDE_BACKUP_DB_USER`, `ZEITWIDE_BACKUP_DB_PASSWORD`, `ZEITWIDE_BACKUP_DATABASES` | `daily`'s `pg_dump` (skipped with a warning when unset) |
| `ZEITWIDE_DATASET_REPOSITORY`, `GITHUB_TOKEN` | `daily`'s release pull request (skipped with a warning when unset) |
| `ZEITWIDE_OPEN_DATA_REPOSITORY`, `GITHUB_DATA_TOKEN`, `ZEITWIDE_HUB_DATASET`, `HF_TOKEN`, `ZEITWIDE_SITE_URL`, `ZEITWIDE_API_PUBLIC_URL` | `daily`'s open data: the GitHub data repository and the Hugging Face dataset (each skipped with a warning without its token; the site URLs are needed for the links) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_SERVICE_NAME` | OTLP export; nothing is exported when unset |

Locally, against `docker compose up` (start the API once first so Flyway creates the
schema):

```bash
ZEITWIDE_DATABASE_CONNINFO=postgresql://zeitwide:zeitwide@localhost:5432/zeitwide ZEITWIDE_SNAPSHOT_BUCKET=zeitwide-snapshots ZEITWIDE_GCS_ENDPOINT=http://localhost:4443 ZEITWIDE_BOT_CONTACT_URL=https://github.com/OWNER/zeitwide uv run zeitwide-pipeline run-due
```

## Schema

Flyway in `api/` owns every table, including Procrastinate's (`V11`, copied from the
pinned `procrastinate==3.10.0`). To upgrade Procrastinate, add a Flyway migration with
its SQL migrations between the two versions, then bump the pin. Never run
`procrastinate schema --apply`.

## Tests

`uv run python -m pytest` runs the unit tests (HTTP mocked with respx) and a
Testcontainers run. That run starts Postgres and fake-gcs-server, applies `api/`'s
migrations, and runs `run-due` end to end as the least-privilege `zeitwide_pipeline`
login. It needs Docker.
