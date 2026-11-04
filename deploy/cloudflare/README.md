# Cloudflare edge (OpenTofu + Worker)

Cloudflare's **Free plan** in front of `zeitwide.com` and `api.zeitwide.com`: DNS proxied, SSL
**Full (strict)**, and one Worker, `edge`, on `zeitwide.com/*`. Nothing here costs money; the
budget note below lists every limit and what happens when one is hit.

| Path | What |
| --- | --- |
| `zone.tf` | The zone (Free plan) and its TLS settings: SSL strict, HTTPS only, TLS 1.2+ |
| `dns.tf` | Apex A/AAAA and `api` CNAME, proxied; verification and DMARC TXT records, not proxied |
| `worker.tf` | The `edge` script and its bindings, its route, two Cron Triggers, the KV namespace, workers.dev off |
| `terraform.tfvars` | Account ID, zone, origin addresses, copied records (nothing secret) |
| `edge/` | The Worker (TypeScript), `bots.json`, unit and Miniflare tests, the curl matrix |

**Origins.** The apex goes to the Cloud Run domain mapping for `zeitwide-web-<env>`, not to
Firebase Hosting: Firebase forwards only the `__session` cookie, which breaks sign-in, so
`deploy/gcp` serves `zeitwide.com` straight from Cloud Run (see `deploy/gcp/envs/dev.tfvars`).
To put Firebase back in front, set `web_origin_ipv4` to Firebase's address. `api.` keeps its
`ghs.googlehosted.com` CNAME. Cloudflare connects with the visitor's Host and SNI, so both Google
certificates keep validating under Full (strict).

## What the Worker does

For each request to `zeitwide.com/*` (`edge/src/index.ts`):

1. **Content negotiation.** A `GET` for a public page (the `matchPublicPath` set in
   `web/src/lib/public-routes.ts`, mirrored in `edge/src/paths.ts`) whose `Accept` prefers
   `text/markdown` is answered with the page's `.md` twin from the origin, with `Vary: Accept`.
   Markdown wins on a higher q, or on equal q when HTML is only matched by a wildcard
   (`text/markdown, */*`) or listed after it. Everything else passes through, and HTML responses
   get `Vary: Accept` added.
2. **Bot labels** from `edge/src/bots.json` (versioned; the version is stored with each data
   point): family (`training`, `search`, `user-fetch`, `agent`, `monitor`, `unknown`, or `human`),
   vendor and token, by the first token found in the User-Agent. OpenAI, Anthropic, Perplexity,
   Google and Apple bots are `verified` only when the client IP is in that vendor's published
   ranges, which a daily cron (03:17 UTC) copies into KV. Every other bot is unverified.
3. **Telemetry:** one Analytics Engine data point per request (below). No IP, no User-Agent, no
   query string, no full path.
4. **AI referrals.** A page view whose `Referer` is chatgpt.com, perplexity.ai, claude.ai,
   gemini.google.com or copilot.microsoft.com (or a subdomain), or whose `utm_source` names one,
   gets `zw_ref=ai:<vendor>` (30 days, `Secure; HttpOnly; SameSite=Lax`). The latest AI referral
   wins. Only the vendor is stored.
5. **Rate limit.** Unverified bots get 60 requests a minute per IP, then `429` with
   `Retry-After: 60`. Verified AI fetchers and people are never limited, and verification costs at
   most one KV read per list per isolate per hour.
6. **Nightly report** (00:30 UTC): yesterday's counts from the Analytics Engine SQL API, pushed to
   Grafana Cloud Loki. The **Edge** dashboard in `deploy/grafana` reads them (panel *Edge bots*).

If the Worker throws, the request goes to the origin as if it weren't there
(`passThroughOnException`).

## Set up (once)

1. **Account and token.** Create a Cloudflare account and note its Account ID (put it in
   `terraform.tfvars`). Create an API token (My Profile > API Tokens) with: *Zone: Zone Edit,
   Zone Settings Edit, DNS Edit, Workers Routes Edit* (all zones in the account) and *Account:
   Workers Scripts Edit, Workers KV Storage Edit, Account Settings Read*. Keep it in your
   shell only: `export CLOUDFLARE_API_TOKEN=...` (it is also what wrangler uses, with
   `CLOUDFLARE_ACCOUNT_ID`).
2. **Build and apply.** State goes in the GCP state bucket, under its own prefix:

   ```bash
   cd deploy/cloudflare/edge && npm ci && npm run build && cd ..
   tofu init -backend-config="bucket=PROJECT_ID-tofu-state" -backend-config="prefix=cloudflare"
   tofu apply
   ```

   Re-run `npm run build` before every apply that should ship Worker changes; the script's hash
   is part of the plan.
3. **Seed the IP ranges** so verification works before the first daily cron:

   ```bash
   cd edge && npm run seed-ranges -- "$(tofu -chdir=.. output -raw ranges_namespace_id)"
   ```

4. **Check the records, then move DNS.** Compare the zone's records in the Cloudflare dashboard
   with GoDaddy's (anything new there goes in `other_records`). Then set the two name servers
   from `tofu output name_servers` at GoDaddy (Domain > DNS > Nameservers > Change > custom).
   The zone turns *Active* within a few hours; until then the site keeps running on GoDaddy DNS.
5. **Nightly report.** Grafana Cloud > your stack > Loki > *Details*: put the push URL
   (`https://logs-prod-<n>.grafana.net/loki/api/v1/push`) and the user (a number) in
   `terraform.tfvars` as `loki_push_url` and `loki_user`, and apply. Then add the two secrets,
   which never go through OpenTofu:

   ```bash
   cd edge
   npx wrangler secret put CF_ANALYTICS_TOKEN --name edge   # a token with Account Analytics: Read
   npx wrangler secret put LOKI_TOKEN --name edge           # Grafana access policy token, logs:write
   ```

6. **API behind Cloudflare.** Cloudflare appends the caller's address to `X-Forwarded-For`
   and Google's front end appends Cloudflare's, so set `api_trusted_proxies = 2` in
   `deploy/gcp/envs/<env>.tfvars` and apply that **before** step 4 makes `api.` proxied.
   Otherwise every anonymous caller shares Cloudflare's address for rate limits.
7. **Fail open.** Dashboard > Workers & Pages > `edge` > Settings > Domains & Routes: set the
   route's *request limit failure mode* to **Fail open** (the provider has no setting for it).
   Then if the free daily request limit runs out, requests skip the Worker and reach the site,
   rather than getting Cloudflare error 1027.

## Budget note: free limits

The Worker runs on **Workers Free** and Analytics Engine's free allowance. Nothing here can
create a bill: on a free account Cloudflare stops serving over the limit, it doesn't charge.
(Limits checked 2026-11-04 on developers.cloudflare.com.)

| Limit (free) | Used by Zeitwide | If exceeded |
| --- | --- | --- |
| Workers requests: **100,000 a day** (resets 00:00 UTC) | One per request to `zeitwide.com`, assets included. `api.` doesn't go through the Worker | With *Fail open* (step 7), requests bypass the Worker until midnight UTC: the site still works, but there's no Markdown negotiation, labels, telemetry or bot limit. With fail closed: error 1027 |
| CPU: **10 ms** per request and per cron run | A string match and a few header edits per request (not measured in production). The ranges cron parses ~2,500 prefixes (a few ms) | That request fails with error 1102. A cron run that fails keeps yesterday's ranges, or loses that night's report (re-run it with `wrangler`). Watch for this in Workers Logs |
| Subrequests: **50** per invocation | 1 per request (the origin), 11 for the ranges cron, 2 for the report | Not reachable as written |
| Cron Triggers: **5** per account | 2 | `tofu apply` fails |
| Workers KV: **100,000 reads, 1,000 writes a day, 1 GB** | Reads only for bots of the five vendors, at most once per list per isolate per hour. 11 writes a day, about 60 KB stored | Reads that fail count as not verified (those bots fall under the 60-a-minute limit) and are retried after a minute. Failed writes keep the old lists |
| Analytics Engine: **100,000 data points written, 10,000 queries a day**; kept 3 months | One point per Worker request, so it can't pass the request cap. 1 query a night | Cloudflare doesn't document free-plan overage. Writing never fails a request; expect lost points |
| Rate limiting binding | Counted per Cloudflare location and approximate | Not a billed product. A bot spread across locations gets a bit more than 60 a minute |
| Workers Logs: **200,000 events a day**, kept 3 days | 1% of requests sampled, plus one line per cron run | Logs stop until midnight UTC |
| Grafana Cloud free Loki: **50 GB a month** | A few hundred lines a night (kilobytes) | Not reachable |

At 100,000 requests a day the site would need roughly 3,000 visitors a day viewing about 30
assets each. Past that, the options are Workers Paid (USD 5 a month for 10 million requests) or
a narrower route (`zeitwide.com/calendars*`, `/docs*` and so on) so static assets skip the Worker.

## Telemetry

Dataset `zeitwide_edge`, one row per request (`edge/src/telemetry.ts`):

| Column | Value |
| --- | --- |
| `index1` | family (the sampling key, so rare bot families aren't sampled away) |
| `blob1` | path template, such as `/calendars/:id/:date` or `/_next/*`; never the path itself |
| `blob2`, `double2` | status |
| `blob3` | `cf-cache-status` (`HIT`, `MISS`, `DYNAMIC`, or `NONE`) |
| `blob4`, `blob5` | family, vendor (`""` for people, `unknown` for unlisted bots) |
| `blob6`, `blob7` | verified, Markdown served (`1`/`0`) |
| `blob8` | country (ISO code, `XX` if unknown) |
| `blob9` | `bots.json` version |
| `double1` | response bytes (Content-Length, or counted as the body streams) |

To check the labels of live traffic (the curl matrix against the deployed zone can't show them):

```bash
curl -s "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/analytics_engine/sql" \
  -H "Authorization: Bearer $CF_ANALYTICS_TOKEN" \
  --data "SELECT blob4 AS family, blob5 AS vendor, blob6 AS verified, blob7 AS markdown, SUM(_sample_interval) AS n FROM zeitwide_edge WHERE timestamp > NOW() - INTERVAL '1' HOUR GROUP BY family, vendor, verified, markdown ORDER BY n DESC FORMAT JSON"
```

The nightly report sends the same counts per path template and status to Loki as one JSON line
each, in stream `{service_name="zeitwide-edge", kind="daily", env, family}`, stamped at noon of
the day they count. Re-sending a day repeats identical entries, which Loki drops, so a retry
never double counts. Loki queries the ingesters only for the last few hours, so a night's lines
show in Grafana once their chunk is flushed (normally within two hours of the push).

## Develop and test

```bash
cd deploy/cloudflare/edge
npm ci
npm run check         # ESLint, Prettier, tsc, Vitest: unit tests plus the built Worker in Miniflare
npm run dev           # wrangler dev on :8787, origin = the live site, labels in x-zw-edge
npm run curl-matrix -- http://127.0.0.1:8787
```

`test/worker.test.ts` runs the built bundle in Miniflare with a fake origin, vendors, SQL API,
Loki and Analytics Engine: negotiation, classification (verified and not, IPv4 and IPv6),
telemetry contents, referral cookies, the 429, and both crons.

**The curl matrix** (`edge/scripts/curl-matrix.sh`, `RATE=1` adds the 429 check):

| Request | Expected body | Expected labels |
| --- | --- | --- |
| Browser User-Agent and Accept | HTML, `Vary` has `Accept` | `family=human; verified=false; markdown=false` |
| GPTBot User-Agent from a non-OpenAI IP | HTML | `family=training; vendor=openai; verified=false` |
| `Accept: text/markdown, text/html;q=0.5` | Markdown (`# ...`), `Content-Type: text/markdown` | `markdown=true` |
| 61 GPTBot requests in a minute, same IP | `429` with `Retry-After: 60` after about 60 | |

Against the deployed zone, labels come from the SQL query above instead of a header.

## Changing bots.json

Add entries most specific first: the first token found in the User-Agent wins, and a test fails
when a token is shadowed by an earlier one it contains. Bump `version`. To verify a bot, its
vendor must be one of the five above and it must list a range source; add new sources under
`ranges` (the vendor's JSON must have a `prefixes` array of `ipv4Prefix`/`ipv6Prefix`).

## Known gaps

- **The origin is still reachable directly.** The run.app URLs and Google's domain-mapping
  addresses answer anyone, so a client can skip Cloudflare, the Worker and its bot limit. Closing
  that needs a check at the origin (a secret header the Worker adds, or Authenticated Origin
  Pulls behind a Google load balancer, which isn't free).
- **Certificate renewal.** Google renews domain-mapping certificates itself. Behind the proxy,
  check the mapping's status after the first renewal
  (`gcloud beta run domain-mappings describe --domain zeitwide.com --region us-central1`). If it
  stalls, switch the record to DNS-only for an hour.
- **Sign-up reads `zw_ref` only on this host.** The cookie has no `Domain`, so the browser sends
  it to `zeitwide.com` alone. Sign-in (`web/src/auth.ts`) stores it on the new account
  (`account.signup_source`, the Adoption dashboard's AI panels) only when the signed-in pages
  (`web_app_origin` in `deploy/gcp`) are served from `zeitwide.com` too. On a separate
  `app.zeitwide.com` the Worker would need `Domain=zeitwide.com`; on a `run.app` host nothing
  arrives.
- Agents that use a plain browser User-Agent are labelled `human`.
