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

# Files for crawlers and AI agents (robots.txt, llms.txt, llms-full.txt, sitemaps) and each
# calendar's CSV, JSON and iCalendar downloads, written by the pipeline with each dataset
# release: every release under its ID, the served copy under current/. Private: only the web
# app reads it and serves current/ at zeitwide.com, so nobody can download from the bucket
# directly and run up egress (see iam.tf).
resource "google_storage_bucket" "site_files" {
  name     = "${var.project_id}-${local.prefix}-site-files"
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  depends_on = [google_project_service.this]
}

# OpenTofu state for deploy/grafana (dashboards and alerts in Grafana Cloud), applied by CI.
# Versioned, so a bad apply's state can be rolled back.
resource "google_storage_bucket" "grafana_state" {
  name     = "${var.project_id}-${local.prefix}-grafana-state"
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      num_newer_versions = 20
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [google_project_service.this]
}
