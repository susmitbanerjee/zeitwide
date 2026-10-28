resource "google_project_service" "this" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "billingbudgets.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "cloudscheduler.googleapis.com",
    "firebasehosting.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "storage.googleapis.com",
    "sts.googleapis.com",
  ])

  service = each.value

  # Both workspaces may share one project; destroying one must not cut off the other.
  disable_on_destroy = false
}

data "google_project" "this" {
  project_id = var.project_id
}
