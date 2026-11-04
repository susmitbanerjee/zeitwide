# The alerts from docs/design.md (Observability), one pair of rule groups per environment.
# Gauges from the pipeline arrive once per run (every 15 minutes), so source queries read the
# last value in the past hour. A source query with no data at all means the pipeline stopped
# reporting, which is itself an alert.
locals {
  rule_groups = merge([for env, job in local.jobs : {
    "sources-${env}" = {
      environment = env
      interval    = 300
      rules = {
        source_stale = {
          summary   = "{{ $labels.zeitwide_source }} has not been checked successfully for over twice its schedule"
          expr      = "max by (zeitwide_source) (time() - last_over_time(zeitwide_source_last_success_timestamp_seconds{job=\"${job.pipeline}\"}[1h])) / max by (zeitwide_source) (last_over_time(zeitwide_source_schedule_seconds{job=\"${job.pipeline}\"}[1h]))"
          op        = "gt"
          threshold = 2
          for       = "0s"
          no_data   = "Alerting"
        }
        source_failing = {
          summary   = "{{ $labels.zeitwide_source }} failed 3 or more checks in a row (parse or fetch); it is suspended until resumed"
          expr      = "max by (zeitwide_source) (last_over_time(zeitwide_source_consecutive_failures{job=\"${job.pipeline}\"}[1h]))"
          op        = "gt"
          threshold = 2
          for       = "0s"
          no_data   = "Alerting"
        }
        candidate_waiting = {
          summary   = "A candidate change has waited over 24 hours for review"
          expr      = "max(last_over_time(zeitwide_review_oldest_candidate_age_seconds{job=\"${job.pipeline}\"}[1h])) / 3600"
          op        = "gt"
          threshold = 24
          for       = "0s"
          # Nothing pending: no age is reported.
          no_data = "OK"
        }
        candidate_short_notice = {
          summary   = "A pending candidate change takes effect in under 14 days"
          expr      = "min(last_over_time(zeitwide_review_min_notice_days{job=\"${job.pipeline}\"}[1h]))"
          op        = "lt"
          threshold = 14
          for       = "0s"
          no_data   = "OK"
        }
      }
    }
    "api-${env}" = {
      environment = env
      interval    = 60
      rules = {
        webhook_success = {
          summary   = "Webhook delivery success is under 99% over the last hour"
          expr      = "sum(increase(zeitwide_webhooks_deliveries_total{job=\"${job.api}\", outcome=\"succeeded\"}[1h])) / sum(increase(zeitwide_webhooks_deliveries_total{job=\"${job.api}\"}[1h]))"
          op        = "lt"
          threshold = 0.99
          for       = "0s"
          # No deliveries in the hour.
          no_data = "OK"
        }
        api_latency = {
          summary   = "API p95 latency is over 200 ms"
          expr      = "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"${job.api}\", uri!~\"/actuator.*|/mcp.*|/internal/.*\"}[5m])))"
          op        = "gt"
          threshold = 0.2
          for       = "10m"
          # Scaled to zero.
          no_data = "OK"
        }
        api_errors = {
          summary   = "Over 1% of API responses are 5xx"
          expr      = "sum(rate(http_server_requests_seconds_count{job=\"${job.api}\", uri!~\"/actuator.*|/mcp.*|/internal/.*\", status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{job=\"${job.api}\", uri!~\"/actuator.*|/mcp.*|/internal/.*\"}[5m]))"
          op        = "gt"
          threshold = 0.01
          for       = "10m"
          no_data   = "OK"
        }
      }
    }
  }]...)
}

resource "grafana_rule_group" "this" {
  for_each = local.rule_groups

  name             = each.key
  folder_uid       = grafana_folder.zeitwide.uid
  interval_seconds = each.value.interval

  dynamic "rule" {
    for_each = each.value.rules
    content {
      name           = "${rule.key} (${each.value.environment})"
      condition      = "C"
      for            = rule.value.for
      no_data_state  = rule.value.no_data
      exec_err_state = "Error"
      labels         = { environment = each.value.environment }
      annotations    = { summary = rule.value.summary }

      data {
        ref_id         = "A"
        datasource_uid = data.grafana_data_source.prometheus.uid
        relative_time_range {
          from = 3600
          to   = 0
        }
        model = jsonencode({ refId = "A", expr = rule.value.expr, instant = true, range = false })
      }

      data {
        ref_id         = "C"
        datasource_uid = "__expr__"
        relative_time_range {
          from = 0
          to   = 0
        }
        model = jsonencode({
          refId      = "C"
          type       = "threshold"
          expression = "A"
          conditions = [{ evaluator = { type = rule.value.op, params = [rule.value.threshold] } }]
        })
      }
    }
  }
}
