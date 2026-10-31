locals {
  db_jdbc_params = "sslmode=verify-full&sslrootcert=${local.db_ca_file}"

  # Spring resolves ${DB_HOST} and ${DB_PORT} from the secret-backed env vars, so the
  # JDBC URL can be plain config. $$ stops OpenTofu interpolating them.
  api_env = merge(local.otel_env, {
    OTEL_SERVICE_NAME                                    = "zeitwide-api"
    JAVA_TOOL_OPTIONS                                    = "-XX:MaxRAMPercentage=75"
    SPRING_DATASOURCE_URL                                = "jdbc:postgresql://$${DB_HOST}:$${DB_PORT}/${local.db.zeitwide_database}?${local.db_jdbc_params}"
    SPRING_DATASOURCE_USERNAME                           = local.db.api_user
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE           = tostring(local.db.api_pool_max)
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = local.issuer
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES  = "zeitwide-api"
    ZEITWIDE_AUTH_JWK_SET_URI                            = "${local.issuer}/protocol/openid-connect/certs"
    ZEITWIDE_AUTH_AUDIENCE                               = "zeitwide-api"
    ZEITWIDE_CORS_ALLOWED_ORIGINS                        = var.web_origin
    ZEITWIDE_MCP_RESOURCE_URL                            = "${trimsuffix(local.api_public_url, "/")}/mcp"
    ZEITWIDE_SNAPSHOT_BUCKET                             = google_storage_bucket.snapshots.name
    ZEITWIDE_OUTBOX_DRAIN_AUDIENCE                       = local.outbox_drain_audience
    ZEITWIDE_OUTBOX_DRAIN_INVOKER                        = google_service_account.this["sched-drain"].email
    ZEITWIDE_RECEIPT_KEY_ID                              = var.receipt_key_id
    ZEITWIDE_RECEIPT_PUBLIC_KEYS_JSON                    = var.receipt_public_keys_json
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
  }

  web_env = merge(local.otel_env, {
    OTEL_SERVICE_NAME    = "zeitwide-web"
    ZEITWIDE_API_URL     = local.api_url
    AUTH_URL             = var.web_origin
    AUTH_TRUST_HOST      = "true"
    AUTH_KEYCLOAK_ISSUER = local.issuer
    AUTH_KEYCLOAK_ID     = "zeitwide-web"
  })

  web_secret_env = {
    AUTH_SECRET                = "web-auth-secret"
    OTEL_EXPORTER_OTLP_HEADERS = "otlp-headers"
  }

  # Always pin the public URL: with strict hostname checks off, Keycloak would build
  # links (password resets, issuer) from a client-supplied X-Forwarded-Host.
  keycloak_env = {
    KC_HOSTNAME             = local.auth_url
    KC_DB                   = "postgres"
    KC_DB_URL_DATABASE      = local.db.keycloak_database
    KC_DB_URL_PROPERTIES    = "?${local.db_jdbc_params}"
    KC_DB_USERNAME          = local.db.keycloak_user
    KC_DB_POOL_INITIAL_SIZE = "1"
    KC_DB_POOL_MIN_SIZE     = "0"
    KC_DB_POOL_MAX_SIZE     = tostring(local.db.keycloak_pool_max)
    GOOGLE_CLIENT_ID        = var.google_client_id
    MICROSOFT_CLIENT_ID     = var.microsoft_client_id
    GITHUB_CLIENT_ID        = var.github_client_id
    ZEITWIDE_WEB_ORIGIN     = var.web_origin
    KC_HTTP_ENABLED         = "true"
    KC_PROXY_HEADERS        = "xforwarded"
    # One instance, and Cloud Run instances can't reach each other, so no cluster: the
    # default JDBC_PING stack would also need a pool of at least 4.
    KC_CACHE                    = "local"
    KC_BOOTSTRAP_ADMIN_USERNAME = "bootstrap-admin"
    JAVA_OPTS_KC_HEAP           = "-XX:MaxRAMPercentage=70"
  }

  keycloak_secret_env = {
    KC_DB_URL_HOST              = "db-host"
    KC_DB_URL_PORT              = "db-port"
    KC_DB_PASSWORD              = "db-keycloak-password" # gitleaks:allow - a Secret Manager secret name, not a value
    KC_BOOTSTRAP_ADMIN_PASSWORD = "keycloak-admin-password"
    GOOGLE_CLIENT_SECRET        = "google-oauth-client-secret"
    MICROSOFT_CLIENT_SECRET     = "microsoft-oauth-client-secret"
    GITHUB_CLIENT_SECRET        = "github-oauth-client-secret"
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
    ZEITWIDE_BACKUP_DATABASES   = "${local.db.zeitwide_database},${local.db.keycloak_database}"
    ZEITWIDE_DATASET_REPOSITORY = var.dataset_repository
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
      min_instance_count = 0
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

resource "google_cloud_run_v2_service" "keycloak" {
  name                = "keycloak-${local.env}"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = local.env == "prod"

  template {
    service_account = google_service_account.this["keycloak"].email

    scaling {
      min_instance_count = 0
      max_instance_count = local.max_instances.keycloak
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
      image = lookup(var.initial_images, "keycloak", var.bootstrap_service_image)
      # The image is pre-built with `kc.sh build` (README), so start skips the build.
      # --import-realm creates the zeitwide realm on first start and then skips it.
      args = ["start", "--optimized", "--import-realm"]

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        # Keycloak works in the background (session expiry, imports) while holding
        # database connections; with CPU only during requests that work stalls and the
        # pool of 3 runs dry. It still scales to zero when idle.
        cpu_idle          = false
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.keycloak_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.keycloak_secret_env
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

      # Keycloak serves /health on its management port (9000). Waiting for readiness,
      # not just an open HTTP port, matters: Cloud Run throttles the CPU once startup is
      # over, and a realm import still running then (first start) crawls for hours. Up to
      # ten minutes for that import.
      startup_probe {
        http_get {
          path = "/health/ready"
          port = 9000
        }
        period_seconds    = 10
        timeout_seconds   = 5
        failure_threshold = 60
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
