---
name: clean-secure-code-review
description: Zeitwide's cross-cutting rules for clean, secure, maintainable code in every part of the repo (naming, small units, no duplicated business rules, design-for-change patterns, /v1 API design, React/Next.js/TypeScript rules for web/, GCP least privilege and cost) plus the security review checklist (OWASP Top 10 and API Security Top 10) and the review output format. Use with the language skills before any code change, when reviewing a diff or pull request, and before declaring a task done.
---

# Clean, secure, maintainable code: cross-cutting rules and review

Use this skill together with the language skills (`java-spring-best-practices`,
`python-best-practices`). It covers what applies everywhere, plus the review procedure.

## 1. Clean code

- Names say intent (`nextBusinessDay`, not `calc2`). Avoid abbreviations except
  well-known domain terms (T+2, RTGS).
- Small units: a function does one thing, and a module has one reason to change.
- No duplication of business rules. The same rule in two places is a bug waiting to
  happen.
- Make illegal states unrepresentable: use typed IDs, enums and sealed types instead of
  strings and booleans.
- Prefer pure functions and immutable data; push I/O to the edges.
- Delete dead code; don't comment it out. No TODOs without an issue link.
- Choose readability over cleverness. If a reviewer has to ask what it does, rewrite it.

## 2. Design for change

- Depend on interfaces at boundaries (ports), not on vendors. GCP, Auth.js and Grafana
  can be swapped behind adapters.
- Use these patterns when the problem calls for them:

  | Pattern | Use it for |
  | --- | --- |
  | Strategy | Interchangeable rules (calendar rule types, `all_open`/`any_open`) |
  | Plugin registry | Adding sources or parsers without editing core code |
  | Ports and adapters | Isolating databases, HTTP, KMS and auth |
  | Outbox | Reliable events and webhooks |
  | Value object | IDs, dates, releases, money |
  | Specification | Composable query filters |
  | Template method | Shared pipeline step skeleton: fetch, parse, diff (sparingly) |

- Don't abstract until the third case. Don't add layers that only forward calls.
- Feature switches and plan entitlements go through one interface.

## 3. API design

- Version in the path (`/v1`); add fields freely, but never remove or rename them within
  a version.
- Make results deterministic and include the dataset release and revision in every
  response.
- Use RFC 9457 problem details with stable `code` values; one error format everywhere.
- Use keyset pagination with opaque cursors and set limits on every list.
- POSTs that create things accept an `Idempotency-Key` header.
- Keep the OpenAPI file as the source of truth and generate the clients (TypeScript,
  Python) from it.

## 4. React / Next.js / TypeScript (`web/`)

- TypeScript `strict: true`, `noUncheckedIndexedAccess: true`; no `any` (use `unknown`
  and narrow).
- Server Components by default; add `'use client'` only where state or effects are
  needed.
- Fetch data with the generated API client plus TanStack Query in client components.
  Never recompute business dates in the browser.
- Never use `dangerouslySetInnerHTML` with API or user data. Set a strict
  Content-Security-Policy and security headers in `next.config` (CSP, HSTS,
  `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`).
- Keep tokens in httpOnly, Secure, SameSite cookies handled by the auth library, never in
  `localStorage`.
- Accessibility: use semantic elements, labels on inputs, visible focus, contrast of
  4.5:1 or more, 44 px touch targets. Check with eslint-plugin-jsx-a11y and axe in
  Playwright tests.
- Tooling: ESLint (next, typescript-eslint strict), Prettier, Vitest for units,
  Playwright for flows. Read `web/AGENTS.md` before changing Next.js code.

## 5. Security review checklist (OWASP Top 10 and API Security Top 10)

For every change, check each item and report any that apply:

1. **Access control:** each endpoint is deny-by-default, object-level checks run (an
   account can only see its own keys and webhooks), and admin routes require the
   `reviewer` role.
2. **Authentication:** tokens are validated by the framework; API keys are hashed and
   compared in constant time; there are no auth bypasses in tests or config.
3. **Injection:** SQL is parameterized, no shell with untrusted input, and output is
   encoded in HTML.
4. **SSRF:** user-supplied URLs (webhooks) are HTTPS only, private IP ranges are blocked
   after DNS resolution, there are no redirects, and timeouts are short.
5. **Unsafe parsing:** XML has external entities off, there's no native
   deserialization, and PDF and file size is capped.
6. **Resource limits:** rate limits are in place, plus request size caps, pagination
   limits and timeouts on all outbound calls.
7. **Secrets:** none in code, config files, logs, errors or the frontend bundle; they
   come from Secret Manager only.
8. **Crypto:** use library primitives only (Ed25519 via Cloud KMS, HMAC-SHA256,
   SHA-256), never home-made crypto.
9. **Misconfiguration:** CORS is an allowlist, security headers are set, debug endpoints
   are off, and actuator exposes health only.
10. **Logging:** security events (login, key created or revoked, webhook changed) are
    logged without sensitive data.
11. **Supply chain:**
    - Dependencies are pinned and locked; Dependabot or Renovate is on.
    - GitHub Actions are pinned by commit SHA.
    - The SBOM is generated (Syft), and images are scanned with Trivy.
    - CodeQL runs on Java, Python and TypeScript, plus gitleaks.

## 6. GCP: least privilege and low cost

- Each service runs as its own service account with only the roles it needs. Never use
  the default compute account or Owner/Editor roles.
- CI deploys through Workload Identity Federation; never create service-account JSON
  keys.
- Prefer scale-to-zero services (Cloud Run services and jobs, Cloud Scheduler). No load
  balancer, NAT or Memorystore unless a measured need appears.
- Set a billing budget with alerts at 50%, 90% and 100%, and label every resource with
  `app` and `env`.
- Use the smallest Cloud SQL tier and grow only on metrics; set log retention and
  exclusion filters to stay in free tiers.

## 7. Review procedure

When asked to review (or before declaring a task done), output:

1. **Summary:** what changed and whether it does what was asked.
2. **Findings, most severe first.** Each one gets a severity (Critical, High, Medium,
   Low), a file and line, the problem, a concrete failure scenario, and the fix.
3. **Checklist results:** the security list above plus the language skill's done list,
   each marked pass, fail or not applicable.
4. **Tests:** what was run and its result (`make check` runs everything). Never claim a
   check passed without running it.

Fix Critical and High findings before merging. Don't pad reviews with style nits that
the formatters already handle.
