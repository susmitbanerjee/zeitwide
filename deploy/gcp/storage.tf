# Raw source snapshots, keyed by SHA-256 and kept forever: they are the evidence
# behind every answer, so Terraform must never empty a bucket during deletion.
resource "google_storage_bucket" "snapshots" {
  name     = "${var.project_id}-${local.prefix}-snapshots"
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true
  }

  depends_on = [google_project_service.this]
}

# Daily pg_dump of both databases. Aiven's free plan has no point-in-time
# recovery, so these dumps are the only backup.
resource "google_storage_bucket" "backups" {
  name     = "${var.project_id}-${local.prefix}-backups"
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [google_project_service.this]
}
