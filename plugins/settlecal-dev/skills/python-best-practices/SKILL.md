---
name: python-best-practices
description: SettleCal's Python 3.12 standards for pipeline/ and lib-python/ (uv + src layout, ruff and mypy strict, functional core with Protocol adapters, source/parser registry, idempotent steps, tz-aware dates with an injected clock, polite httpx scraping, parameterized psycopg SQL, safe XML/PDF/YAML parsing, structlog and OpenTelemetry, golden snapshot tests, public library rules). Use before writing, changing or reviewing any Python file or pyproject.toml under pipeline/ or lib-python/.
---

# Python 3.12 standards

Apply these rules to every Python change in `pipeline/` and `lib-python/`. If a rule
must be broken, say why in the pull request description.

## 1. Project setup

- Use `uv` for environments and locking: `pyproject.toml` plus a committed `uv.lock`,
  with `src/` layout.
- Ruff handles both linting and formatting (at least rules `E,F,W,I,B,UP,SIM,S,N,RUF,PL`;
  the repo's `pyproject.toml` files select more, so keep them). Run mypy with `--strict`
  (or pyright strict) in CI.
- Use pre-commit hooks for ruff, mypy, gitleaks and the end-of-file fixers.
- Pin the Python version (`requires-python = ">=3.12"`) and test that version in CI.

## 2. Structure

- Keep a functional core and an imperative shell:
  - Parsers and diff logic are pure functions
    (`parse(snapshot_bytes, meta) -> list[Observation]`) with no I/O.
  - I/O (HTTP, database, storage) lives in thin adapter modules behind
    `typing.Protocol` interfaces.
- Register sources and parsers through a small plugin registry:
  - A `Source` protocol plus one module per source.
  - Adding a calendar means adding a module, never editing a big `if`/`elif`.
- Every pipeline step is idempotent: re-running it with the same input changes nothing,
  keyed by snapshot hash.
- No module-level side effects (no network calls, database connections or config reads
  at import time).

## 3. Code style

- Type every public function. Use `dataclass(frozen=True, slots=True)` for internal
  values, and Pydantic v2 models only at boundaries (API payloads, config, files).
- Use `datetime` with timezones only (`zoneinfo`), and `date` for calendar days. Never
  use a naive `datetime.now()`; inject a clock.
- Prefer small functions, early returns and descriptive names. No mutable default
  arguments, no bare `except:`, no `print` in library code.
- Use `Enum`/`StrEnum` for closed sets (`Basis.PUBLISHED`, `Basis.PROJECTED`).
- Use `pathlib` for paths and context managers for resources.
- Comments explain why. Docstrings on public functions use Google style.

## 4. HTTP and scraping

- Use `httpx` with an explicit timeout on every call (connect 5 s, read 30 s), plus
  retries with backoff and jitter (`tenacity`) for 5xx and timeouts only.
- Send an honest User-Agent with a contact URL, respect `robots.txt` and source terms,
  and add at most one request per second per host.
- Use conditional requests (`ETag`, `If-Modified-Since`) and store raw bytes before
  parsing.
- Check HTTP status and content type. A page that parses to zero rows is an error, not
  "no change".

## 5. Data and persistence

- Use psycopg 3 with parameterized queries only. Never use f-strings or `%` formatting
  for SQL.
- Keep transactions short; one publish is one transaction (entry versions, change event
  and outbox row together).
- Run jobs through the Postgres-backed queue (Procrastinate), with a unique job key per
  source and run window.
- The schema belongs to `api/` (Flyway). Python never creates or alters tables.

## 6. Security

- Parse XML with `defusedxml`, or `lxml` with `resolve_entities=False, no_network=True`.
- Use pdfplumber or pypdf with page and size limits for PDFs. Never shell out with
  untrusted input; if a subprocess is needed, pass a list of args, never `shell=True`.
- Never call `pickle`, `eval`, `exec` or `yaml.load` on data. Use `yaml.safe_load` and
  `json`.
- Secrets come from environment variables (injected from Secret Manager). Never commit
  them or log them.
- Cap download sizes, check redirect targets, and reject non-HTTPS sources unless they
  are allowlisted.
- Run bandit (via ruff `S` rules), `pip-audit` and gitleaks in CI.

## 7. Logging and observability

- Use `structlog` with JSON output and bound context (`source_id`, `snapshot_sha256`,
  `calendar`). Never log secrets.
- Use the OpenTelemetry SDK: one span per pipeline step, and record exceptions on the
  span. Export metrics for check freshness, parse failures and candidates pending.

## 8. Testing

- pytest plus Hypothesis for date logic, for example that business-day addition is
  monotonic and never lands on a closed day.
- Every parser gets golden tests: a stored real snapshot (bytes) in
  `tests/fixtures/<source>/` and the expected observations as JSON. When a source
  changes layout, add the new snapshot; never delete old ones.
- Mock HTTP with `respx`. Never call real sources in unit tests.
- Use Testcontainers PostgreSQL for repository and publish-step tests.
- The shared conformance suite (`conformance/cases/`) must pass in the open library.
- Coverage gate: 85% on parsers and diff logic (the package-wide floor in
  `pyproject.toml` is 80%).

## 9. Public library rules (`lib-python/`, the open-source package)

- Follow semantic versioning; the public API is what `__all__` exports. Ship `py.typed`.
- Deprecate before removing: warn for at least one minor release.
- Keep dependencies minimal (standard library plus `httpx` for the online client).
  Offline mode works with no network.
- The library must give the same answers as the Java API for the same dataset release,
  proven by the conformance suite.

## Verify

From `pipeline/` or `lib-python/` (on Windows, keep the `python -m` form):

```bash
uv run python -m ruff format . && uv run python -m ruff check .
uv run python -m mypy
uv run python -m pytest --cov
```

## 10. Done checklist

- [ ] ruff, mypy strict, bandit, pip-audit and gitleaks are green.
- [ ] New parsers have golden tests from real snapshots, and a zero-row result fails
      loudly.
- [ ] Network calls have timeouts and retries, and no I/O happens at import time.
- [ ] SQL is parameterized; steps are idempotent.
- [ ] Logs are structured, with no secrets; spans cover the new step.
