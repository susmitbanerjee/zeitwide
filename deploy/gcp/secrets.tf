# Containers only: no google_secret_manager_secret_version here, so no secret
# value ever reaches code or state. Add versions by hand (README).
resource "google_secret_manager_secret" "this" {
  for_each = local.secrets

  secret_id = "${local.prefix}-${each.key}"

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.this]
}

resource "google_secret_manager_secret_iam_member" "readers" {
  for_each = local.secret_access

  secret_id = google_secret_manager_secret.this[each.value.secret].id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.this[each.value.reader].member
}
