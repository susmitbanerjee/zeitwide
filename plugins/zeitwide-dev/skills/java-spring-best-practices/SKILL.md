---
name: java-spring-best-practices
description: Zeitwide's Java 21 + Spring Boot 3 standards for the api/ module (package-by-feature ports and adapters, records and sealed types, java.time only, JdbcClient bitemporal SQL, ProblemDetail errors, deny-by-default security, SSRF-safe webhooks, JUnit/Testcontainers/jqwik testing). Use before writing, changing or reviewing any Java, Gradle or Flyway file under api/.
---

# Java 21 + Spring Boot 3 standards

Apply these rules to every Java change. If a rule must be broken, say why in the pull
request description.

## 1. Structure

- Package by feature, not by layer: `answers`, `calendars`, `changes`, `receipts`,
  `webhooks`, `auth`, `accounts`.
- Inside each feature use ports and adapters:
  - `domain/` holds plain Java: records, sealed types, pure logic. It has no Spring,
    JDBC or Jackson imports.
  - `app/` holds use-case services that orchestrate domain and ports. Transactions live
    here.
  - `api/` holds controllers, MCP tools, request/response DTOs and mappers.
  - `infra/` holds JDBC repositories, HTTP clients, KMS and other adapters.
- Enforce the layering with ArchUnit tests (`api/src/test/java/com/zeitwide/api/ArchitectureTest.java`):
  `domain` must not depend on `api` or `infra`, and controllers must not touch
  repositories.
- One public entry point per feature (for example `AnswerEngine`). Other features call
  only that entry point.

## 2. Language

- Use records for DTOs and value objects (`CalendarId`, `DatasetRelease`), validated in
  the compact constructor.
- Use sealed interfaces plus pattern-matching `switch` for closed result types
  (`Open | Closed | Unknown`). No default branch, so the compiler catches missing cases.
- Prefer immutability: `final` fields, `List.copyOf`, no setters on domain types.
- Dates use `java.time` only (`LocalDate`, `Instant`, `ZoneId`). Never `Date`,
  `Calendar`, or the JVM default timezone. Pass a `Clock` so time is testable.
- Return `Optional` only from methods; never take it as a parameter or field. Return
  empty collections, never null.
- No magic numbers or strings; name them as constants or config.
- Methods do one thing and stay short (about 30 lines max); classes have one reason to
  change.
- Comments explain why, never what.

## 3. Spring

- Constructor injection only. No `@Autowired` on fields.
- Config is `@ConfigurationProperties` records with `@Validated`. Fail fast at startup on
  bad config.
- `@Transactional` goes on `app/` service methods only (`readOnly = true` for queries),
  never on controllers or repositories.
- Controllers only map HTTP to a use case and back. No business logic in them.
- Errors are RFC 9457 `ProblemDetail` from one `@RestControllerAdvice`, with stable
  machine `code` values (`unknown_calendar`, `out_of_range`).
- Validate request DTOs with Bean Validation (`@Valid`, `@NotNull`, `@Size`), and set max
  sizes on every list and string.
- Turn on virtual threads with `spring.threads.virtual.enabled=true`.
- Set `spring.jpa.open-in-view=false`, or better, skip JPA: use `JdbcClient` (or jOOQ)
  for the bitemporal SQL so the queries stay explicit.
- Flyway migrations are append-only. Never edit a merged migration.

## 4. Persistence

- Parameterized SQL only; never build SQL by string concatenation.
- Use explicit column lists, never `SELECT *` in application code.
- Page with keyset pagination (`WHERE id > :cursor`), not OFFSET.
- Each query that runs in a request has a supporting index. Check `EXPLAIN` in review.
- Keep transactions short, with no network calls inside one.
- Domain events go through the outbox table in the same transaction as the change.
- Keep the Hikari pool small (max 5 per instance) because small Cloud SQL tiers allow few
  connections.

## 5. Errors and logging

- Never swallow an exception. Catch only to add context or translate, and always keep
  the cause.
- Use domain exceptions for expected failures; let unexpected ones reach the advice
  handler.
- Log with SLF4J structured key-values (`log.atInfo().addKeyValue("calendar", id)`),
  JSON in production, `trace_id` from OpenTelemetry.
- Never log secrets, tokens, API keys, webhook secrets, request bodies with personal
  data, or full email addresses.

## 6. Security (check every change)

- Spring Security denies by default. Every route is listed in the filter chain as
  public, authenticated or role-based.
- JWTs are validated as an OAuth2 resource server (issuer, audience, expiry). Never parse
  tokens by hand.
- API keys: store a prefix plus a SHA-256 hash, compare with `MessageDigest.isEqual`, and
  show the full key only once.
- CORS uses an explicit origin allowlist; no `*` with credentials.
- Secrets come from environment variables injected by Secret Manager, never from
  `application.yml` or code.
- Webhook targets get SSRF protection: HTTPS only, resolve DNS and reject private,
  loopback and link-local addresses, pin the resolved IP, use short timeouts and no
  redirects.
- Never use Java native deserialization on untrusted data. Jackson keeps default typing
  off.
- Set rate limits and request size limits on every public endpoint.
- Run SpotBugs with FindSecBugs, OWASP Dependency-Check or Trivy, and Gradle dependency
  locking.

## 7. Design patterns

Use:

- Strategy for calendar rule types (weekend rules, observance rules).
- Ports and adapters for anything external.
- Outbox for events.
- Specification objects for complex queries.
- Value objects instead of raw `String`/`long` IDs.

Avoid:

- God services and static utility sprawl.
- Inheritance for code reuse (prefer composition).
- Abstractions with one implementation, unless they are a port. Wait for the third case
  before abstracting.

Feature flags and entitlements go through one interface
(`Entitlements.check(account, feature)`) so plans can change without touching features.

## 8. Testing

- JUnit 5 + AssertJ. Domain tests are plain unit tests with no Spring context.
- Use `@WebMvcTest` slices for controllers and Testcontainers PostgreSQL for
  repositories. Never H2.
- The conformance suite (golden JSON cases in `conformance/cases/`, shared with Python)
  must pass in CI.
- Use property-based tests (jqwik) for date arithmetic, for example: adding N days then
  subtracting N returns a business day on or before the start.
- Coverage gate: 80% line coverage on `domain/` and `app/` (JaCoCo). Try PIT mutation
  testing on the answer engine.
- Each test checks one behavior, named `shouldSkipFedwireClosureWhenAddingDays`.

## 9. Build and tooling

- Gradle Kotlin DSL with a version catalog (`api/gradle/libs.versions.toml`) and
  dependency locking.
- Spotless with google-java-format, plus Error Prone. The build fails on warnings in
  `domain/`.
- Build container images with Jib on a distroless Java 21 base, running as non-root.
- On Cloud Run:
  - Turn on Spring AOT and Class Data Sharing for faster cold starts.
  - Size the heap for 512Mi with `-XX:MaxRAMPercentage=75`.
  - Expose `/actuator/health/liveness` and `/readiness`.

Verify with `./gradlew check` from `api/` (Spotless, Error Prone, tests, ArchUnit,
JaCoCo); fix formatting with `./gradlew spotlessApply`.

## 10. Done checklist

- [ ] Layering rules pass (ArchUnit); no domain class imports Spring.
- [ ] Inputs validated, errors are `ProblemDetail` with codes, nothing sensitive logged.
- [ ] New queries are parameterized, indexed and inside short transactions.
- [ ] Tests cover the happy path, edge cases (weekends, year ends, leap days, timezones)
      and failure paths.
- [ ] Security scans and dependency checks are green; no new secrets in the diff.
- [ ] Public API changes are reflected in the OpenAPI file and are backward compatible.
