locals {
  # The same file the Worker reads, so its cron dispatch and the triggers cannot drift.
  schedules = jsondecode(file("${path.module}/edge/src/schedules.json"))
}

resource "cloudflare_workers_kv_namespace" "ranges" {
  account_id = var.account_id
  title      = "zeitwide-edge-ranges"
}

# Code, bindings and plain settings. The two secrets (CF_ANALYTICS_TOKEN, LOKI_TOKEN) are added
# with `wrangler secret put` (README) so they never reach OpenTofu state; keep_bindings keeps
# them across applies.
resource "cloudflare_workers_script" "edge" {
  account_id         = var.account_id
  script_name        = "edge"
  main_module        = "index.js"
  content_file       = "${path.module}/${var.worker_bundle}"
  content_sha256     = filesha256("${path.module}/${var.worker_bundle}")
  compatibility_date = "2026-08-01"
  keep_bindings      = ["secret_text"]

  bindings = concat(
    [
      { name = "RANGES", type = "kv_namespace", namespace_id = cloudflare_workers_kv_namespace.ranges.id },
      { name = "EDGE_EVENTS", type = "analytics_engine", dataset = "zeitwide_edge" },
      # Unverified bots: 60 requests a minute per IP (the Worker answers 429, Retry-After: 60).
      {
        name         = "BOT_LIMIT"
        type         = "ratelimit"
        namespace_id = "1001"
        simple       = { limit = 60, period = 60 }
      },
      { name = "ENVIRONMENT", type = "plain_text", text = var.environment },
      { name = "CF_ACCOUNT_ID", type = "plain_text", text = var.account_id },
    ],
    var.loki_push_url == "" ? [] : [
      { name = "LOKI_PUSH_URL", type = "plain_text", text = var.loki_push_url },
      { name = "LOKI_USER", type = "plain_text", text = var.loki_user },
    ],
  )

  # Workers Logs (free: 200,000 events a day, kept 3 days). Cron runs log one line each; 1% of
  # requests are sampled.
  observability = {
    enabled            = true
    head_sampling_rate = 0.01
  }
}

# No workers.dev URL: the Worker answers only on the zone, where its fetch reaches the origin.
resource "cloudflare_workers_script_subdomain" "edge" {
  account_id       = var.account_id
  script_name      = cloudflare_workers_script.edge.script_name
  enabled          = false
  previews_enabled = false
}

resource "cloudflare_workers_route" "edge" {
  zone_id = local.zone_id
  pattern = "${var.zone_name}/*"
  script  = cloudflare_workers_script.edge.script_name
}

# Two of the free plan's five Cron Triggers per account.
resource "cloudflare_workers_cron_trigger" "edge" {
  account_id  = var.account_id
  script_name = cloudflare_workers_script.edge.script_name
  schedules = [
    { cron = local.schedules.ranges },
    { cron = local.schedules.report },
  ]
}
