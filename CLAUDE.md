# SettleCal

SettleCal answers "is this a business day, and what is the next one?" for payment rails
(Fedwire, FedACH, FedNow, T2, CHAPS) and public holiday calendars, with every answer
backed by evidence. Python monitors official sources, parses them into observations,
and publishes a change only after two independent sources agree and a human reviewer
signs off. PostgreSQL keeps every version of every date bitemporally, so any answer can
be replayed as of any past moment. Java serves the answers through one REST API, a
hosted MCP server and webhooks, and signs each answer with a verifiable receipt. The
same versioned dataset ships as an open Python library and local MCP server. The full
design is in [docs/design.md](docs/design.md).

## Before coding

**Load the three skills before coding:** `java-spring-best-practices`,
`python-best-practices` and `clean-secure-code-review`. They ship in the `settlecal-dev`
plugin under `plugins/`, which `.claude/settings.json` enables for this repo (accept the
folder trust prompt once). After editing a skill, run `/reload-plugins`.

## Rules that shape every change

- **Python writes data, Java serves it, they meet in Postgres.** The pipeline never
  serves answers and the API never ingests sources. Neither calls the other; they share
  only the database schema (Flyway migrations, owned by `api/`).
- **The web app never computes dates.** Every date on screen, including the
  calculator, comes from the API. No date arithmetic in `web/`.
- Business-day rules are data in the dataset release, not code in either language.
  Java and Python must both pass every case in `conformance/cases/`.

## Layout

| Path | What | Stack |
| --- | --- | --- |
| `api/` | REST, MCP, webhooks, receipts, Flyway migrations | Java 21, Spring Boot 3, Gradle Kotlin DSL + version catalog |
| `pipeline/` | Monitors, parsers, diff, review CLI, release builder | Python 3.12, uv, src layout |
| `lib-python/` | Open library (MIT) + local MCP server | Python 3.12, uv, src layout |
| `web/` | Answer pages, app, admin, docs | Next.js, React, TypeScript strict |
| `conformance/` | Golden cases both languages must pass | JSON |
| `dataset/` | Exported dataset releases | JSON |
| `deploy/` | Keycloak realm, Postgres init, fake GCS buckets | |
| `plugins/` | Claude Code plugins (coding-standard skills), listed in `.claude-plugin/marketplace.json` | Markdown, JSON |

## Running locally

Everything at once: `make check` runs every linter and test suite; `make fmt` fixes
formatting; `make setup` installs the pre-commit hooks and dependencies.
`api/openapi.yaml` is the API contract: after changing it, `make generate-clients`
regenerates `web/src/api/schema.ts` and `lib-python/src/settlecal/api_types.py` (tests in
both fail while they are stale, and an `api/` test fails if the controllers and the file
disagree).

**Local stack** (Postgres 18 on 5432, Keycloak on 8180, fake GCS on 4443):

```bash
docker compose up -d --wait   # or: make up
docker compose down           # or: make down
```

Keycloak admin console: http://localhost:8180 (admin / admin locally; override in `.env`,
see `.env.example`). Realm `settlecal`, public client `settlecal-web`, role `reviewer`.

**api/** needs a JDK 17+ as `JAVA_HOME` to run Gradle; it compiles with a Java 21 toolchain.

```bash
cd api
./gradlew bootRun          # http://localhost:8080/actuator/health
./gradlew check            # Spotless, Error Prone, tests, ArchUnit, JaCoCo
./gradlew spotlessApply
```

**pipeline/** and **lib-python/**

```bash
cd pipeline                                 # or lib-python
uv sync
uv run python -m ruff format . && uv run python -m ruff check .
uv run python -m mypy                       # strict
uv run python -m pytest --cov
uv run settlecal-pipeline health            # pipeline only
```

**web/**

```bash
cd web
npm ci
npm run dev                # http://localhost:3000, health at /api/health
npm run check              # ESLint, Prettier, tsc, Vitest
```

## Tooling

- Java: Spotless (google-java-format), Error Prone (`-Werror`), JaCoCo (80% lines),
  ArchUnit. Versions live in `api/gradle/libs.versions.toml`.
- Python: ruff (lint + format), mypy strict, pytest + coverage (80%).
- Web: ESLint (next + prettier), Prettier, `tsc --noEmit`, Vitest (80% coverage).
- Repo: pre-commit with gitleaks (needs the `gitleaks` binary on PATH), hygiene hooks
  and ruff. CI runs the same `make` targets and scans the full git history with gitleaks.

## Windows notes

- Run Python tools as `python -m <tool>` (as the Makefile does). Windows Smart App
  Control can block unsigned launchers in `.venv\Scripts`.
- If `uv sync` makes a venv whose `python.exe` is blocked, recreate it on the signed
  python.org build: `uv sync --python <path to python.org 3.12>\python.exe`.
