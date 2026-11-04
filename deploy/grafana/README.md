# Grafana Cloud: dashboards and alerts (OpenTofu)

Telemetry goes straight from each service to the Grafana Cloud free plan over OTLP, with no
collector on GCP ([docs/design.md](../../docs/design.md), Observability). This folder holds
what lives in Grafana itself, as code:

| File | Creates |
| --- | --- |
| `main.tf` | Folder `Zeitwide` and the dashboards in `dashboards/` (Sources, API, Adoption, Edge) |
| `alerts.tf` | The alert rules from the design doc: rule groups `sources-<env>` and `api-<env>` |
| `contact.tf` | Email contact point (`alert_email`) and the stack's notification policy |

## What is sent, and how it stays free

| Signal | From | How |
| --- | --- | --- |
| Traces | API (OpenTelemetry Java agent), web server (`@vercel/otel`), pipeline (SDK) | 10% of traces plus every trace with an error. Each service records every span and decides when a trace's local root ends, by trace ID (the same rule in `TraceIdRatio.java`, `trace-sampling.ts` and `telemetry.py`), so a kept trace is whole across services. Health checks are dropped |
| Metrics | API (Micrometer OTLP registry), pipeline (SDK) | Labels are operation, calendar, tool, outcome, source, route template and status only. Never an API key, account, IP, URL or date |
| Logs | API (agent, logback) | Also JSON on stdout for Cloud Logging, with `trace_id` and `span_id`. The pipeline logs JSON with `trace_id` to Cloud Logging, and the web server logs each failed request that way |
| Browser | Grafana Faro | Errors and Web Vitals from 10% of sessions; page URLs lose their query string |

Grafana Cloud turns `service.namespace/service.name` into the `job` label, such as
`zeitwide-prod/zeitwide-api`, so dev and prod share the one stack (cleverhare3418). The
dashboards switch environment with a variable; `environments` (default `["dev"]`) says which
ones get alert rules.

The **Edge** dashboard reads Loki, not Prometheus: the Cloudflare Worker's nightly cron pushes
yesterday's Analytics Engine counts there as one JSON line per label set
(`{service_name="zeitwide-edge", kind="daily"}`, `../cloudflare/README.md`). Its *Edge bots* panel
shows yesterday by default; pick the stack's Loki data source (`grafanacloud-<stack>-logs`).

## Alerts

| Rule | Fires when | No data means |
| --- | --- | --- |
| `source_stale` | Time since a source's last successful check is over 2× its schedule | The pipeline stopped reporting: alert |
| `source_failing` | 3 or more failed checks in a row (parse or fetch); the source is suspended | Alert |
| `candidate_waiting` | The oldest pending candidate is over 24 h old | Nothing pending: OK |
| `candidate_short_notice` | A pending candidate changes a date less than 14 days away | OK |
| `webhook_success` | Delivery success under 99% over 1 h | No deliveries: OK |
| `api_latency` | p95 over 200 ms for 10 min | Scaled to zero: OK |
| `api_errors` | 5xx over 1% for 10 min | OK |

The pipeline reports source and review-queue gauges from the database on every run (every
15 minutes), not just for the sources it checked, so a daily source or a suspended one still
reports. The Java vs Python conformance alert from the design doc is the CI job, not a rule here.

## Set up Grafana Cloud (once)

The stack already has its hosted Prometheus (Mimir), Loki and Tempo data sources; there is
nothing to install or scrape. Check the Prometheus one is named
`grafanacloud-cleverhare3418-prom` (Connections > Data sources); if not, set
`prometheus_data_source`.

1. **OTLP ingest.** Connections > Add new connection > OpenTelemetry (OTLP): create a token
   with `metrics:write`, `logs:write` and `traces:write`. Put the endpoint and the
   `Authorization=Basic%20<base64 of instance-id:token>` header in Secret Manager as
   `zeitwide-dev-otlp-endpoint` and `zeitwide-dev-otlp-headers` (`deploy/gcp/README.md`).
2. **Token for this folder.** Administration > Users and access > Service accounts: add
   `zeitwide-ci` with the Editor role (dashboards, folders, alert rules, contact points and
   notification policies), create a token and store it:
   `printf '%s' "<token>" | gcloud secrets versions add zeitwide-dev-grafana-token --data-file=-`
3. **Faro.** Frontend Observability > Create app (allowed origin `https://zeitwide.com`). Set
   its collector URL as `faro_url` in `deploy/gcp/envs/dev.tfvars` and as the GitHub
   repository variable `ZEITWIDE_FARO_URL` (the web image is built with it). It is public:
   browsers post to it.
4. Email needs no SMTP setup: Grafana Cloud sends it.

## How it is published

`.github/workflows/grafana.yml` checks this folder on pull requests and applies it on every
push to `main` that touches it (or by hand, with *Run workflow*). It signs in to GCP as the
deployer through Workload Identity Federation, reads `zeitwide-dev-grafana-token`, and keeps
state in `<project>-zeitwide-dev-grafana-state` (both created by `deploy/gcp`; apply that
first). Change dashboards by editing the JSON here: changes made in the Grafana UI are
overwritten on the next apply, and `grafana_notification_policy` owns the stack's whole policy
tree. `alert_email` defaults to susmit.b1@gmail.com.

To apply from your machine instead:

```bash
cd deploy/grafana
tofu init -backend-config="bucket=<project>-zeitwide-dev-grafana-state" -backend-config="prefix=grafana"
export GRAFANA_AUTH="$(gcloud secrets versions access latest --secret zeitwide-dev-grafana-token)"
tofu apply
```

## Try it locally

`grafana/otel-lgtm` runs Grafana, Prometheus, Tempo, Loki and an OTLP endpoint in one container:

```bash
docker run -d --name zeitwide-lgtm -p 127.0.0.1:3001:3000 -p 127.0.0.1:4318:4318 grafana/otel-lgtm:0.36.0
```

Point the services at `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318` with
`OTEL_RESOURCE_ATTRIBUTES=service.namespace=zeitwide-dev` (and, for the API, the agent flags
in `api/build.gradle.kts` after `./gradlew otelFiles`). To apply this folder to it, copy it
somewhere with a `backend_override.tf` holding `terraform { backend "local" {} }`, and use
`GRAFANA_AUTH=admin:admin`, `grafana_url=http://localhost:3001` and
`prometheus_data_source=Prometheus`. The local container has no SMTP server, so alerts fire
and route to the email contact point but the email itself is not sent.
