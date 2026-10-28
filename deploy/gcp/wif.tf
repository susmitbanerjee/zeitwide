# GitHub Actions signs in with its OIDC token; no service-account keys exist.
resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github-${local.env}"
  display_name              = "GitHub Actions (${local.env})"

  depends_on = [google_project_service.this]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }

  attribute_mapping = {
    "google.subject"          = "assertion.sub"
    "attribute.repository"    = "assertion.repository"
    "attribute.repository_id" = "assertion.repository_id"
    "attribute.ref"           = "assertion.ref"
  }

  # Only tokens from this repository, on an allowed ref, are accepted at all.
  attribute_condition = format(
    "assertion.repository_id == %q && assertion.ref in %s",
    var.github_repository_id,
    jsonencode(var.github_deploy_refs),
  )
}

resource "google_service_account_iam_member" "github_deployer" {
  service_account_id = google_service_account.this["deployer"].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository_id/${var.github_repository_id}"
}
