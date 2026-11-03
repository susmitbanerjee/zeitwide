locals {
  db_jdbc_params = "sslmode=verify-full&sslrootcert=${local.db_ca_file}"

  # Spring resolves ${DB_HOST} and ${DB_PORT} from the secret-backed env vars, so the
  # JDBC URL can be plain config. $$ stops OpenTofu interpolating them.
  api_env = merge(local.otel_env, {
    OTEL_SERVICE_NAME                          = "zeitwide-api"
    JAVA_TOOL_OPTIONS                          = "-XX:MaxRAMPercentage=75"
    SPRING_DATASOURCE_URL                      = "jdbc:postgresql://$${DB_HOST}:$${DB_PORT}/${local.db.zeitwide_database}?${local.db_jdbc_params}"
    SPRING_DATASOURCE_USERNAME                 = local.db.api_user
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = tostring(local.db.api_pool_max)
    # The API accepts only tokens the web app signed (the secret is in api_secret_env).
    ZEITWIDE_AUTH_ISSUER          = local.web_app_origin
    ZEITWIDE_AUTH_AUDIENCE        = "zeitwide-api"
    ZEITWIDE_CORS_ALLOWED_ORIGINS = join(",", distinct([var.web_origin, local.web_app_origin]))
    # The web app's key gets the first-party daily limit (create-web-api-key.sh prints its ID).
    ZEITWIDE_RATE_LIMITS_FIRST_PARTY_KEY_IDS = join(",", var.first_party_api_key_ids)
    ZEITWIDE_SNAPSHOT_BUCKET                 = google_storage_bucket.snapshots.name
    ZEITWIDE_OUTBOX_DRAIN_AUDIENCE           = local.outbox_drain_audience
    ZEITWIDE_OUTBOX_DRAIN_INVOKER            = google_service_account.this["sched-drain"].email
    ZEITWIDE_RECEIPT_KEY_ID                  = var.receipt_key_id
    ZEITWIDE_RECEIPT_PUBLIC_KEYS_JSON        = var.receipt_public_keys_json
    # Cloud Run's front end appends the client's address to X-Forwarded-For.
    ZEITWIDE_RATE_LIMITS_TRUSTED_PROXIES = "1"
  })

  api_secret_env = {
    DB_HOST                         = "db-host"
    DB_PORT                         = "db-port"
    SPRING_DATASOURCE_PASSWORD      = "db-api-password" # gitleaks:allow - a Secret Manager secret name, not a value
    ZEITWIDE_RECEIPT_SIGNING_KEY    = "receipt-signing-key"
    ZEITWIDE_WEBHOOK_ENCRYPTION_KEY = "webhook-encryption-key"
    OTEL_EXPORTER_OTLP_HEADERS      = "otlp-headers"
    ZEITWIDE_AUTH_TOKEN_SECRET      = "api-token-secret"
  }

  web_env = merge(local.otel_env, {
    OTEL_SERVICE_NAME = "zeitwide-web"
    # Server to server on the run.app URL: it works before any custom domain does.
    ZEITWIDE_API_URL = google_cloud_run_v2_service.api.uri
    ZEITWIDE_MCP_URL = "${local.api_public_url}/mcp"
    SITE_URL         = var.web_origin
    # robots.txt, llms.txt, the sitemaps and the calendar downloads, written by the pipeline with
    # each release. The bucket is private: the web app reads it as its own service account.
    SITE_FILES_URL  = "https://storage.googleapis.com/${google_storage_bucket.site_files.name}/current"
    SITE_FILES_AUTH = "gcp-metadata"
    APP_URL         = local.web_app_origin
    AUTH_URL        = local.web_app_origin
    AUTH_TRUST_HOST = "true"
    # A sign-in provider is offered only when its client ID is set (secrets are in web_secret_env).
    AUTH_GOOGLE_ID           = var.google_client_id
    AUTH_MICROSOFT_ID        = var.microsoft_client_id
    AUTH_GITHUB_ID           = var.github_client_id
    ZEITWIDE_REVIEWER_EMAILS = join(",", var.reviewer_emails)
  })

  web_secret_env = {
    AUTH_SECRET                = "web-auth-secret"
    ZEITWIDE_API_KEY           = "web-api-key"
    ZEITWIDE_API_TOKEN_SECRET  = "api-token-secret"
    AUTH_GOOGLE_SECRET         = "google-oauth-client-secret"
    AUTH_MICROSOFT_SECRET      = "microsoft-oauth-client-secret"
    AUTH_GITHUB_SECRET         = "github-oauth-client-secret"
    OTEL_EXPORTER_OTLP_HEADERS = "otlp-headers"
  }

  # libpq variables, so psycopg and pg_dump both pick them up with no extra config.
  pipeline_env = merge(local.otel_env, {
    OTEL_SERVICE_NAME           = "zeitwide-pipeline"
    PGUSER                      = local.db.pipeline_user
    PGDATABASE                  = local.db.zeitwide_database
    PGSSLMODE                   = "verify-full"
    PGSSLROOTCERT               = local.db_ca_file
    ZEITWIDE_DB_POOL_MAX_SIZE   = tostring(local.db.pipeline_pool_max)
    ZEITWIDE_BOT_CONTACT_URL    = var.bot_contact_url
    ZEITWIDE_SNAPSHOT_BUCKET    = google_storage_bucket.snapshots.name
    ZEITWIDE_BACKUP_BUCKET      = google_storage_bucket.backups.name
    ZEITWIDE_BACKUP_DB_USER     = local.db.backup_user
    ZEITWIDE_BACKUP_DATABASES   = local.db.zeitwide_database
    ZEITWIDE_DATASET_REPOSITORY = var.dataset_repository
    ZEITWIDE_SITE_FILES_BUCKET  = google_storage_bucket.site_files.name
    ZEITWIDE_SITE_URL           = var.web_origin
    ZEITWIDE_API_PUBLIC_URL     = local.api_public_url
  })

  pipeline_secret_env = {
    PGHOST                      = "db-host"
    PGPORT                      = "db-port"
    PGPASSWORD                  = "db-pipeline-password" # gitleaks:allow - a Secret Manager secret name, not a value
    ZEITWIDE_BACKUP_DB_PASSWORD = "db-backup-password"   # gitleaks:allow - a Secret Manager secret name, not a value
    GITHUB_TOKEN                = "github-release-token"
    OTEL_EXPORTER_OTLP_HEADERS  = "otlp-headers"
  }
}

resource "google_cloud_run_v2_service" "api" {
  name                = "${local.app}-api-${local.env}"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = local.env == "prod"

  template {
    service_account = google_service_account.this["api"].email
    timeout         = "60s"

    scaling {
      min_instance_count = var.api_min_instances
      max_instance_count = local.max_instances.api
    }

    volumes {
      name = "db-ca"
      secret {
        secret = google_secret_manager_secret.this["db-ca-cert"].secret_id
        items {
          version = "latest"
          path    = "ca.pem"
        }
      }
    }

    containers {
      image = lookup(var.initial_images, "api", var.bootstrap_service_image)

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.api_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.api_secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.this[env.value].secret_id
              version = "latest"
            }
          }
        }
      }

      volume_mounts {
        name       = "db-ca"
        mount_path = local.db_ca_mount_dir
      }

      startup_probe {
        http_get {
          path = "/actuator/health/readiness"
        }
        period_seconds    = 5
        timeout_seconds   = 3
        failure_threshold = 24
      }

      liveness_probe {
        http_get {
          path = "/actuator/health/liveness"
        }
        period_seconds = 30
      }
    }
  }

  # CI deploys the images; gcloud also stamps the client fields.
  lifecycle {
    ignore_changes = [client, client_version, template[0].containers[0].image]
  }

  depends_on = [google_secret_manager_secret_iam_member.readers]
}

resource "google_cloud_run_v2_service" "web" {
  name                = "${local.app}-web-${local.env}"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = local.env == "prod"

  template {
    service_account = google_service_account.this["web"].email

    scaling {
      min_instance_count = 0
      max_instance_count = local.max_instances.web
    }

    containers {
      image = lookup(var.initial_images, "web", var.bootstrap_service_image)

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.web_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.web_secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.this[env.value].secret_id
              version = "latest"
            }
          }
        }
      }

      startup_probe {
        http_get {
          path = "/api/health"
        }
        period_seconds    = 5
        timeout_seconds   = 3
        failure_threshold = 12
      }
    }
  }

  # CI deploys the images; gcloud also stamps the client fields.
  lifecycle {
    ignore_changes = [client, client_version, template[0].containers[0].image]
  }

  depends_on = [google_secret_manager_secret_iam_member.readers]
}

resource "google_cloud_run_v2_job" "pipeline" {
  name                = "${local.app}-pipeline-${local.env}"
  location            = var.region
  deletion_protection = local.env == "prod"

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account = google_service_account.this["pipeline"].email
      max_retries     = 1
      # Under the 15-minute schedule, so two regular runs never overlap.
      timeout = "840s"

      volumes {
        name = "db-ca"
        secret {
          secret = google_secret_manager_secret.this["db-ca-cert"].secret_id
          items {
            version = "latest"
            path    = "ca.pem"
          }
        }
      }

      containers {
        image = lookup(var.initial_images, "pipeline", var.bootstrap_job_image)
        args  = ["run-due"]

        resources {
          limits = {
            cpu    = "1"
            memory = "512Mi"
          }
        }

        dynamic "env" {
          for_each = local.pipeline_env
          content {
            name  = env.key
            value = env.value
          }
        }

        dynamic "env" {
          for_each = local.pipeline_secret_env
          content {
            name = env.key
            value_source {
              secret_key_ref {
                secret  = google_secret_manager_secret.this[env.value].secret_id
                version = "latest"
              }
            }
          }
        }

        volume_mounts {
          name       = "db-ca"
          mount_path = local.db_ca_mount_dir
        }
      }
    }
  }

  # CI deploys the image; gcloud also stamps the client fields.
  lifecycle {
    ignore_changes = [client, client_version, template[0].template[0].containers[0].image]
  }

  depends_on = [google_secret_manager_secret_iam_member.readers]
}

# Signed-in pages on their own domain, straight to Cloud Run (see web_app_origin).
resource "google_cloud_run_domain_mapping" "web_app" {
  count    = var.web_app_domain == null ? 0 : 1
  name     = var.web_app_domain
  location = var.region

  metadata {
    namespace = var.project_id
    labels    = local.labels
  }

  spec {
    route_name = google_cloud_run_v2_service.web.name
  }
}

# The public API on its own domain (verified in Search Console first; README, step 11).
resource "google_cloud_run_domain_mapping" "api" {
  count    = var.api_domain == null ? 0 : 1
  name     = var.api_domain
  location = var.region

  metadata {
    namespace = var.project_id
    labels    = local.labels
  }

  spec {
    route_name = google_cloud_run_v2_service.api.name
  }
}
