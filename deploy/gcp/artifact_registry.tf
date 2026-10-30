resource "google_artifact_registry_repository" "images" {
  repository_id = local.prefix
  location      = var.region
  format        = "DOCKER"
  description   = "Zeitwide container images (${local.env})"

  cleanup_policy_dry_run = false

  # KEEP only works alongside a DELETE policy: delete everything except the three
  # most recent versions of each image.
  cleanup_policies {
    id     = "delete-all"
    action = "DELETE"
    condition {
      tag_state = "ANY"
    }
  }

  cleanup_policies {
    id     = "keep-last-3"
    action = "KEEP"
    most_recent_versions {
      keep_count = 3
    }
  }

  depends_on = [google_project_service.this]
}
