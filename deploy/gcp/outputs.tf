output "service_urls" {
  description = "run.app URLs of the Cloud Run services."
  value = {
    api      = google_cloud_run_v2_service.api.uri
    web      = google_cloud_run_v2_service.web.uri
    keycloak = google_cloud_run_v2_service.keycloak.uri
  }
}

output "image_repository" {
  description = "Docker repository CI pushes to."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "github_workload_identity_provider" {
  description = "Value for google-github-actions/auth `workload_identity_provider`."
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "github_deployer_service_account" {
  description = "Value for google-github-actions/auth `service_account`."
  value       = google_service_account.this["deployer"].email
}

output "buckets" {
  description = "Snapshot and backup bucket names."
  value = {
    snapshots = google_storage_bucket.snapshots.name
    backups   = google_storage_bucket.backups.name
  }
}

output "secret_ids" {
  description = "Secret Manager secrets that need a value added by hand."
  value       = sort([for s in google_secret_manager_secret.this : s.secret_id])
}
