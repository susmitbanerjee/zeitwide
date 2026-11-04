# Health checks are most of the request log lines and tell nobody anything. Excluded lines
# are never stored, so they don't count against Cloud Logging's free 50 GiB a month.
resource "google_logging_project_exclusion" "health_checks" {
  name        = "${local.prefix}-health-checks"
  description = "Request logs of health checks on the ${local.env} API and web services"
  filter      = <<-EOT
    resource.type = "cloud_run_revision"
    resource.labels.service_name = ("${google_cloud_run_v2_service.api.name}" OR "${google_cloud_run_v2_service.web.name}")
    log_id("run.googleapis.com/requests")
    (httpRequest.requestUrl : "/actuator/health" OR httpRequest.requestUrl : "/api/health")
  EOT

  depends_on = [google_project_service.this]
}
