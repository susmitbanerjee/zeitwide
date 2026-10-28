locals {
  pipeline_run_uri = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/jobs/${google_cloud_run_v2_job.pipeline.name}:run"
  scheduler_tz     = "Etc/UTC"
}

# Every 15 minutes: run the source checks that are due (the job's default args).
resource "google_cloud_scheduler_job" "pipeline_due" {
  name      = "${local.prefix}-pipeline-due"
  region    = var.region
  schedule  = "*/15 * * * *"
  time_zone = local.scheduler_tz

  attempt_deadline = "60s"
  retry_config {
    retry_count = 0
  }

  http_target {
    http_method = "POST"
    uri         = local.pipeline_run_uri

    oauth_token {
      service_account_email = google_service_account.this["sched-run"].email
      scope                 = "https://www.googleapis.com/auth/cloud-platform"
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.scheduler_runs_pipeline]
}

# Every minute: drain the webhook outbox. The API checks this OIDC token's
# issuer, audience and service-account email before doing anything.
resource "google_cloud_scheduler_job" "outbox_drain" {
  name      = "${local.prefix}-outbox-drain"
  region    = var.region
  schedule  = "* * * * *"
  time_zone = local.scheduler_tz

  attempt_deadline = "30s"
  # The next run a minute later is the retry.
  retry_config {
    retry_count = 0
  }

  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}/internal/outbox/drain"

    oidc_token {
      service_account_email = google_service_account.this["sched-drain"].email
      audience              = local.outbox_drain_audience
    }
  }

  depends_on = [google_project_service.this]
}

# Daily: cut a dataset release, pg_dump both databases to the backups bucket,
# and clean up (expired rate-limit rows, old job records).
resource "google_cloud_scheduler_job" "pipeline_daily" {
  name      = "${local.prefix}-pipeline-daily"
  region    = var.region
  schedule  = "15 3 * * *"
  time_zone = local.scheduler_tz

  attempt_deadline = "60s"
  retry_config {
    retry_count = 1
  }

  http_target {
    http_method = "POST"
    uri         = local.pipeline_run_uri
    headers = {
      "Content-Type" = "application/json"
    }
    body = base64encode(jsonencode({
      overrides = {
        containerOverrides = [{ args = ["daily"] }]
        timeout            = "1800s"
      }
    }))

    oauth_token {
      service_account_email = google_service_account.this["sched-run"].email
      scope                 = "https://www.googleapis.com/auth/cloud-platform"
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.scheduler_runs_pipeline]
}
