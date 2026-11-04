# Zeitwide MVP — System Design

Oct 28, 2026 · @Susmit Banerjee

## Summary

The Zeitwide MVP has three parts: Python ingests and verifies calendar data, PostgreSQL stores every version of every date, and Java serves answers through the REST API, the hosted MCP server and webhooks. One versioned dataset, checked by a shared test suite, keeps the Java and Python code giving the same answers. Everything is free in the MVP, but each account already has a plan field, so billing can be added later without changing the schema.

| Area | Decision | Why |
| --- | --- | --- |
| Web UI | React + TypeScript on Next.js | Public answer pages need server rendering for SEO (M8); the app and admin share one codebase |
| Serving layer | Java 21, Spring Boot 3 | REST API, hosted MCP server, webhook dispatch, receipt signing |
| Data layer | Python 3.12 | Source monitors, parsers, diffing, review tooling, the open library and the local MCP server |
| Database | PostgreSQL 18, bitemporal tables | As-of answers (M2) need every version kept with valid and recorded time |
| Snapshots | Google Cloud Storage (fake-gcs-server locally) | Raw source pages stored by SHA-256 for provenance (M1) |
| Cache and limits | Caffeine in-process cache; rate limits in Postgres (Bucket4j) | Rate limits per key, hot-answer cache |
| Job queue | Postgres-backed queue (Procrastinate) | One less system to run; jobs share the database's transactions |
| Auth | Auth.js in the web app | Google, Microsoft and GitHub sign-in with no identity server to run; the API trusts short-lived tokens the web app signs |
| Observability | OpenTelemetry → Grafana Cloud free plan (metrics, logs, traces, Faro) | You asked for Grafana; OTel keeps code vendor-neutral |
| Billing | None in MVP; `plan` and `entitlements` tables from day one | Free adoption now; paid plans later without a migration |

## Architecture

The split follows one rule: Python owns everything that **writes** calendar data, and Java owns everything that **serves** it. Neither layer calls the other directly; they meet in PostgreSQL. That keeps the hot read path (API, MCP) in one fast JVM service and the messy scraping and parsing in Python, where those libraries are strongest.

&#91;embedded content: MVP architecture · Python writes, Postgres remembers, Java serves\]

The release builder also exports each dataset release to GitHub and bundles it into the open Python library and local MCP server. Start each language as **one deployable** (a modular monolith), not microservices: `zeitwide-api` (Java) and `zeitwide-pipeline` (Python workers), plus the Next.js web app.

**Keeping two languages honest.** Java (the API) and Python (the open library) both compute business days. To stop them drifting:

- The rules (weekend definitions, observance rules, `all_open` vs `any_open`) live as data in the release, not as code in either language.
- A shared conformance suite of golden cases (`conformance/cases/*.json`: input, expected date, expected skipped days) runs in both CI pipelines. A release can't ship if either fails.
- The UI never computes dates itself. It always calls the API.

## Data pipeline

A calendar change reaches customers in seven steps. Nothing is published without two agreeing sources and a human sign-off, which is the verification moat (M4) turned into code.

1. **Monitor.** A Python job per source runs on its schedule (daily by default, hourly near known announcement dates). It fetches the page or PDF, computes SHA-256, and stores the raw bytes in object storage only when the hash changes.
2. **Parse.** A parser per source turns the snapshot into *observations*: "source X says date D on calendar C is closed, reason R". Parsers are versioned, and every observation keeps its snapshot hash and parser version.
3. **Diff.** New observations are compared with the current published entries. No difference means nothing happens. A difference opens a *candidate change*.
4. **Corroborate.** The candidate waits until a second independent source agrees (for example, Fed Holiday Schedules plus Board K.8). Single-source changes are flagged, not dropped.
5. **Review.** You approve or reject it in the admin review screen, which shows both snapshots side by side. Urgent changes (less than 14 days' notice) jump the queue and alert you.
6. **Publish.** One database transaction closes the old entry version, writes the new one, bumps the calendar revision, and writes a `change_event` plus an outbox row.
7. **Release and notify.** The release builder cuts a dataset release (`2026.10.28.1`), exports it to GitHub and packages, and the Java dispatcher sends webhooks from the outbox.

If a source page fails to fetch or parse three times in a row, the job stops and alerts. A broken scraper must never look like "no change".

## Database

Use PostgreSQL 18 as the single system of record, with **bitemporal** calendar entries. Every entry row has two time ranges: the date it describes, and when Zeitwide believed it (`recorded_during`). Rows are never updated in place; a change closes the old row's `recorded_during` and inserts a new one. An as-of query is then one indexed lookup:

```sql
SELECT * FROM calendar_entry
WHERE calendar_id = 'GB-CHAPS'
  AND entry_date = '2022-09-19'
  AND recorded_during @> timestamptz '2022-09-09 12:00Z';
```

A GiST exclusion constraint on `(calendar_id, entry_date, recorded_during)` stops two versions from overlapping. Migrations are owned by the Java service (Flyway); Python reads and writes through the same schema.

| Table | Holds | Written by |
| --- | --- | --- |
| `calendar` | ID, name, kind (rail, public, exchange), timezone, weekend rule, current revision | Admin |
| `source` | Publisher, URL, parser name, schedule, role (primary or corroborating) | Admin |
| `source_snapshot` | Source, fetched at, SHA-256, object-storage key, HTTP status | Python monitor |
| `observation` | Snapshot, calendar, date, open/closed, reason, parser version | Python parser |
| `candidate_change` | Proposed before/after, supporting observations, status, reviewer | Python + admin |
| `calendar_entry` | Calendar, date, open, reason, basis (published/projected), `recorded_during`, revision | Publish step |
| `entry_source` | Which snapshots back each entry version | Publish step |
| `change_event` | Type, calendar, date, before/after JSON, notice days, published at | Publish step |
| `outbox` | Events waiting for webhook delivery | Publish step |
| `dataset_release` | Release ID, created at, calendar revisions, export checksum | Release builder |
| `account`, `app_user` | Organization, members, sign-in subject (`google:1234`), `plan` = `free`, `signup_source` (`ai:<vendor>` when an AI assistant referred the sign-up, else null) | Java |
| `api_key` | Prefix, SHA-256 of the secret, scopes, last used, revoked at | Java |
| `webhook_subscription`, `webhook_delivery` | Endpoint, secret, event filter; attempts and responses | Java |
| `entitlement` | Plan to feature limits (empty checks in MVP) | Java |

Receipts are stateless and are never written to Postgres: their payload and signature travel with the answer. Raw snapshots go to Cloud Storage, keyed by hash, kept forever. They are the evidence behind every answer. Cached answers live in each API instance's memory (Caffeine) and rate-limit counters in Postgres, so there is no cache server to run or pay for. Back up Postgres with point-in-time recovery from day one, because the change history can't be rebuilt.

## API, MCP and webhooks

All three surfaces in the mockups (REST, MCP and webhooks) live in one Spring Boot service and call the same `AnswerEngine` class, so a person and an agent can never get different dates.

- **REST API (v1).** Check a date, add business days, list calendars and closures, page through changes, and verify receipts. Answer routes accept `as_of`; their responses carry the effective instant, dataset release, per-calendar revisions and a receipt. `GET /v1/changes` filters by calendars, `since` and event types, and uses an opaque keyset cursor. Spring MVC on virtual threads (Java 21). Define the contract in OpenAPI and generate the Python and TypeScript clients from it.
- **Hosted MCP server.** The six tools from the C2 mockup, built on the official MCP Java SDK (Spring AI MCP server) over Streamable HTTP at `/mcp`. Each tool is a thin wrapper over `AnswerEngine` with a strict JSON schema and returns the same structured answer, sources and receipt as REST. MCP clients send an API key as a bearer token.
- **Local MCP server.** Shipped in Python inside the open library and run with `uvx zeitwide-mcp --offline` on the bundled dataset. *This changes the mockup, which showed `npx`, to keep the stack to Java and Python.*
- **Webhooks.** Authenticated accounts manage subscriptions with `GET/POST /v1/account/webhooks`, `PUT/DELETE /v1/account/webhooks/{id}`, and `POST /v1/account/webhooks/{id}/test`. A create response shows the signing secret once; the database keeps only its AES-GCM ciphertext under a key from Secret Manager. Accept HTTPS URLs only, resolve every address before saving and before delivery, reject private, loopback, link-local and metadata ranges, and pin that validated DNS answer for the request. The HTTP client follows no redirects and uses a five-second timeout. Cloud Scheduler calls `POST /internal/outbox/drain` every minute. It claims rows with `FOR UPDATE SKIP LOCKED`, fans out only to matching event and calendar subscriptions, sends stable event IDs and a timestamped HMAC-SHA256 `Zeitwide-Signature` header, and records every attempt in `webhook_delivery`. Failed deliveries use exponential backoff for up to 24 hours, then become terminal failures. A receiver can deduplicate retries by event ID.
- **Receipts.** Every answer gets a self-contained receipt over RFC 8785 canonical JSON for `{request, answer, dataset_release, calendar_revisions, issued_at}`. Its SHA-256 payload hash and Ed25519 signature are returned with the payload; `POST /v1/receipts/verify` checks it without a receipt lookup or database row. Keep the PKCS#8 private key in GCP Secret Manager, never in Postgres. `GET /.well-known/zeitwide-keys.json` publishes the SPKI public key ring with each key's validity interval. On rotation, deploy the new active key and keep prior public keys in the ring so older receipts still verify. The JDK's built-in Ed25519 provider signs and verifies receipts.
- **Determinism.** Each response carries `dataset_release` and calendar revisions in its body and headers. Cache answers by `(request, release)`, because a release never changes once cut.
- **Public pages without a key.** Anonymous requests are allowed at a low rate limit, so answer pages and casual users work without signing up.

## Auth

There is no identity server. The web app signs people in with **Auth.js**, straight to Google, Microsoft and GitHub, so Zeitwide never stores a password and there is nothing extra to run, start or pay for.

| Need | How it is covered |
| --- | --- |
| Google, Microsoft, GitHub login | Auth.js providers; a button shows only when its OAuth app is configured |
| Web app sessions | An encrypted, httpOnly session cookie holding who the person is and their roles |
| Calls to the API | The web app's server signs a one-minute HS256 token (issuer, audience, subject, roles, and `signup_source` when the edge Worker's `zw_ref` cookie named an AI vendor at sign-in) for each call; the API stores `signup_source` only on the account it creates, and only an exact `ai:<vendor>` value; the API checks the signature, issuer, audience and lifetime, and the browser never holds one |
| Admin review screen | The `reviewer` role, given at sign-in to a verified Google address on the `reviewer_emails` list |
| MCP clients | An API key as a bearer token on `/mcp` |
| Enterprise SSO | A Microsoft work account signs in through the same Microsoft button; per-customer SAML is future work |

API keys are for machines, not people. Keys look like `zw_live_…`. Store only the prefix and a SHA-256 hash of the secret, and show the full key once. The API accepts either a web-signed token or an API key, and both resolve to the same account.

An address that fails a key check too often is turned away before the database is asked. OAuth for MCP clients (an authorization server with dynamic client registration) is not built; add one when a client needs it.

## Frontend

Build one Next.js app (React 19, TypeScript) with three areas. A plain single-page React app would hide the answer pages from search engines, and those pages are the main way people find Zeitwide (M8).

| Area | Screens from the mockups | Rendering | Auth |
| --- | --- | --- | --- |
| Public answer pages | A1, calendar year pages | Static generation, refreshed when a release is cut | None |
| App | A2 explorer, A3 evidence, A4 calculator, A5 time travel, A6 changes, C1 keys and webhooks, C2 MCP setup | Client components calling the API | Sign-in |
| Admin | Review queue, source health, releases | Client components | `reviewer` role |
| Docs | C3, C4 API reference | Static pages generated from the OpenAPI file | None |

- **UI kit:** shadcn/ui on Tailwind, with TanStack Query for API calls. Generate the typed API client from OpenAPI so the web app and the API can't disagree.
- **Answer pages at scale:** pre-build one page per calendar per year plus the next 90 days. Generate other dates on first request and cache them. Revalidate a calendar's pages when its revision changes.
- **The web app never computes dates.** Every date on screen comes from the API, including the calculator.

## Observability

Every service exports OpenTelemetry (OTLP) straight to the **Grafana Cloud free plan**, which covers metrics, logs and traces. The web app reports browser errors and page speed through Grafana Faro. There's no collector to run on GCP. Cloud Run's own logs still land in Cloud Logging, within its free tier.

- **Java:** the OpenTelemetry Java agent plus Micrometer gives HTTP, JDBC and JVM metrics and traces with no code changes.
- **Python:** OpenTelemetry SDK, with one trace per source check (fetch, parse, diff), so a broken parser shows up as a failed span.
- **Logs:** JSON lines with `trace_id`, so Grafana jumps from a log line to its trace.
- **Staying free:** keep 10% of traces plus every trace with an error, decided by trace ID so web, API and pipeline keep the same ones whole; label metrics by calendar, operation, tool and outcome, never by API key, IP or date. Dashboards and alerts are code in `deploy/grafana/`.

For this product, **data freshness is the main health signal**, not uptime. A source that silently stopped updating is worse than a 500 error.

| Signal | Alert when |
| --- | --- |
| Hours since last successful check, per source | Over 2× its schedule |
| Parse failures in a row, per source | 3 or more |
| Candidate changes waiting for review | Any older than 24 h, or any with under 14 days' notice |
| Webhook delivery success rate | Under 99% over 1 h |
| API p95 latency, error rate | p95 over 200 ms, or 5xx over 1% |
| Java vs Python conformance results | Any mismatch in CI |

Build three dashboards: **Sources** (freshness and failures per source), **API** (traffic, latency, errors, MCP tool calls by tool), and **Adoption** (sign-ups, active keys, top calendars). The adoption dashboard tells you which calendars to add next.

## Free MVP, ready for paid plans

In the MVP every account is on the `free` plan and gets everything: as-of history, receipts, webhooks and the hosted MCP server. There's no checkout, card or invoice code. Three small things now avoid a painful migration later:

- **Plan and entitlements exist from day one.** Every request passes through one `Entitlements.check(account, feature)` call, which always says yes in the MVP. Turning on paid plans is then a data change, not a rewrite.
- **Rate limits per key**, generous but real (for example 10,000 calls a day). This stops abuse and shows you what heavy users would pay for.
- **Usage is metered** per account and feature into a `usage_daily` table, even though nobody is billed. That data sets your prices later.

**Decide the license before the first public release; it can't be taken back.** Data published under a permissive license (CC BY or CC0) stays free for commercial use forever, even if you change the license later. So:

- Publish the open dataset as **CC BY-NC 4.0** from the first release, as the open-core plan assumes.
- Give commercial users free use during the MVP through the **service terms**, for example "free commercial use of the hosted API until \[DATE\], with 60 days' notice before paid plans".
- Publish the library and local MCP server under **MIT**.

## Repo, deployment and plan

Keep everything in one monorepo so a schema change, the API and the conformance cases move together in one pull request.

```
zeitwide/
  api/            Java 21, Spring Boot: REST, MCP, webhooks, receipts, Flyway migrations
  pipeline/       Python: monitors, parsers, review CLI, release builder
  lib-python/     open library (MIT) + local MCP server
  web/            Next.js: answer pages, app, admin, docs
  conformance/    golden test cases both languages must pass
  dataset/        exported releases (mirrored to the public data repo)
  deploy/         Docker Compose, GCP config, Alloy and Grafana config
```

**Deployment on GCP, for about $12–15 a month.** Everything lives in us-central1:

- The API and web app run as Cloud Run services that scale to zero.
- The pipeline runs as Cloud Run Jobs, started by Cloud Scheduler.
- Cloud SQL `db-f1-micro` holds both databases.
- Cloud Storage holds snapshots.
- Firebase Hosting gives a free CDN and TLS in front of the web app.
- Secret Manager holds every secret, including the receipt signing key.

There's no load balancer, Memorystore or VPC connector. GitHub Actions deploys through Workload Identity Federation. The implementation prompts doc has the full cost table and the infrastructure prompt.

&#91;embedded content: MVP roadmap · 4 phases, 3 gates (estimate for one person)\]

The MVP covers Fedwire, FedACH, FedNow, T2 and CHAPS, plus US federal, England and Wales, and German public holidays. That's enough to match every screen in the mockups.

| Risk | What to do |
| --- | --- |
| Java and Python give different answers | Rules as data, shared conformance suite, the UI never computes dates |
| A source page changes layout and a parser breaks silently | Freshness alerts, fail loudly after 3 bad parses, keep the snapshot |
| Two languages slow one person down | Python only in the pipeline and library; all serving in Java; generated clients |
| Wrong date published | Two-source rule, review step, and `basis: projected` when an operator hasn't published |
| Cold starts | Scale-to-zero services take up to 30 seconds to start, so the site wakes the API when a visitor arrives |
| Source terms of use | Check each source's reuse terms before adding it; store facts and links, not copied page text |
