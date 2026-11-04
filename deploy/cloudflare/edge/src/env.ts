/** Bindings set in deploy/cloudflare/worker.tf (and wrangler.toml for local runs). */
export interface Env {
  /** Vendor IP range lists, refreshed daily by the ranges cron. */
  readonly RANGES: KVNamespace;
  /** One data point per request (dataset zeitwide_edge). */
  readonly EDGE_EVENTS: AnalyticsEngineDataset;
  /** 60 requests a minute per IP, applied to unverified bots only. */
  readonly BOT_LIMIT?: RateLimit;
  /** dev or prod: the environment label on the Grafana series. */
  readonly ENVIRONMENT: string;
  /** For the nightly report: Analytics Engine SQL API. */
  readonly CF_ACCOUNT_ID?: string;
  /** Secret (wrangler secret put): API token with Account Analytics Read. */
  readonly CF_ANALYTICS_TOKEN?: string;
  /** Grafana Cloud Loki push URL, such as https://logs-prod-012.grafana.net/loki/api/v1/push. */
  readonly LOKI_PUSH_URL?: string;
  /** Grafana Cloud Loki instance (user) ID. */
  readonly LOKI_USER?: string;
  /** Secret: Grafana Cloud access policy token with logs:write. */
  readonly LOKI_TOKEN?: string;
  /** Local runs only: send requests here instead of the request's own host. */
  readonly ORIGIN?: string;
  /** Local runs only: "1" adds an x-zw-edge response header with the labels. */
  readonly DEBUG_LABELS?: string;
}
