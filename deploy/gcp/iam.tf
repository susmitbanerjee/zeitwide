# One account per workload. Roles are granted on the individual resource
# (secret, bucket, job, repository) wherever GCP allows it, not on the project.
resource "google_service_account" "this" {
  for_each = local.service_accounts

  account_id   = "${local.app}-${each.key}-${local.env}"
  display_name = "${local.prefix} ${each.key}"
  description  = each.value

  depends_on = [google_project_service.this]
}

# Pipeline: create-only on both buckets, so it can't overwrite or delete evidence
# or backups. Old backups expire through the bucket lifecycle rule instead.
resource "google_storage_bucket_iam_member" "pipeline_snapshots_create" {
  bucket = google_storage_bucket.snapshots.name
  role   = "roles/storage.objectCreator"
  member = google_service_account.this["pipeline"].member
}

resource "google_storage_bucket_iam_member" "pipeline_snapshots_read" {
  bucket = google_storage_bucket.snapshots.name
  role   = "roles/storage.objectViewer"
  member = google_service_account.this["pipeline"].member
}

resource "google_storage_bucket_iam_member" "pipeline_backups_create" {
  bucket = google_storage_bucket.backups.name
  role   = "roles/storage.objectCreator"
  member = google_service_account.this["pipeline"].member
}

# API: reads snapshots for the evidence screen.
resource "google_storage_bucket_iam_member" "api_snapshots_read" {
  bucket = google_storage_bucket.snapshots.name
  role   = "roles/storage.objectViewer"
  member = google_service_account.this["api"].member
}

# Scheduler: may start the pipeline job, with argument overrides for the daily run.
resource "google_cloud_run_v2_job_iam_member" "scheduler_runs_pipeline" {
  name     = google_cloud_run_v2_job.pipeline.name
  location = var.region
  role     = "roles/run.jobsExecutorWithOverrides"
  member   = google_service_account.this["sched-run"].member
}

# The API, web app and Keycloak are public at the Cloud Run layer; the apps do
# their own auth. /internal/outbox/drain checks the scheduler's OIDC token in the API.
resource "google_cloud_run_v2_service_iam_member" "public" {
  for_each = {
    api      = google_cloud_run_v2_service.api.name
    web      = google_cloud_run_v2_service.web.name
    keycloak = google_cloud_run_v2_service.keycloak.name
  }

  name     = each.value
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# Deployer (GitHub Actions): push images, roll out new revisions, deploy
# Firebase Hosting. It can't change IAM, secrets or any other infrastructure.
resource "google_artifact_registry_repository_iam_member" "deployer_push" {
  repository = google_artifact_registry_repository.images.name
  location   = var.region
  role       = "roles/artifactregistry.writer"
  member     = google_service_account.this["deployer"].member
}

resource "google_cloud_run_v2_service_iam_member" "deployer_services" {
  for_each = {
    api      = google_cloud_run_v2_service.api.name
    web      = google_cloud_run_v2_service.web.name
    keycloak = google_cloud_run_v2_service.keycloak.name
  }

  name     = each.value
  location = var.region
  role     = "roles/run.developer"
  member   = google_service_account.this["deployer"].member
}

resource "google_cloud_run_v2_job_iam_member" "deployer_pipeline" {
  name     = google_cloud_run_v2_job.pipeline.name
  location = var.region
  role     = "roles/run.developer"
  member   = google_service_account.this["deployer"].member
}

# Rolling out a revision means acting as that revision's runtime account.
resource "google_service_account_iam_member" "deployer_acts_as_runtime" {
  for_each = toset(["api", "web", "keycloak", "pipeline"])

  service_account_id = google_service_account.this[each.key].name
  role               = "roles/iam.serviceAccountUser"
  member             = google_service_account.this["deployer"].member
}

# Building the web image prerenders answer pages with the web app's own API key, so the
# deploy workflow reads that one secret.
resource "google_secret_manager_secret_iam_member" "deployer_web_api_key" {
  secret_id = google_secret_manager_secret.this["web-api-key"].id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.this["deployer"].member
}

# Firebase Hosting has no per-site IAM role, so this one is project-wide.
resource "google_project_iam_member" "deployer_hosting" {
  project = var.project_id
  role    = "roles/firebasehosting.admin"
  member  = google_service_account.this["deployer"].member
}
