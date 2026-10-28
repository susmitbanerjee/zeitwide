locals {
  env    = terraform.workspace
  app    = "settlecal"
  prefix = "${local.app}-${local.env}"

  labels = {
    app = local.app
    env = local.env
  }

  # Postgres runs on Aiven's free plan, which allows 20 connections. The per-client
  # pool caps below keep the worst case at 17 (README has the table).
  db = {
    settlecal_database = "settlecal"
    keycloak_database  = "keycloak"
    api_user           = "settlecal_api"
    pipeline_user      = "settlecal_pipeline"
    backup_user        = "settlecal_backup"
    keycloak_user      = "keycloak"
    api_pool_max       = 5
    pipeline_pool_max  = 2
    keycloak_pool_max  = 3
  }

  db_ca_mount_dir = "/secrets/db-ca"
  db_ca_file      = "${local.db_ca_mount_dir}/ca.pem"

  max_instances = {
    api      = 2
    web      = 2
    keycloak = 1
  }

  realm = "settlecal"

  # Runtime identities. Scheduler jobs get their own two accounts so the token that
  # can start the pipeline is never the one sent to the API.
  service_accounts = {
    api         = "Cloud Run service settlecal-api"
    web         = "Cloud Run service settlecal-web"
    keycloak    = "Cloud Run service keycloak"
    pipeline    = "Cloud Run job settlecal-pipeline"
    sched-run   = "Cloud Scheduler: starts the pipeline job"
    sched-drain = "Cloud Scheduler: calls the API outbox drain"
    deployer    = "GitHub Actions deploys via Workload Identity Federation"
  }

  # Secret containers only. Values are added by hand (README), never in code.
  # Each secret lists the runtime accounts that may read it.
  secrets = {
    "db-host"                 = ["api", "pipeline", "keycloak"]
    "db-port"                 = ["api", "pipeline", "keycloak"]
    "db-ca-cert"              = ["api", "pipeline", "keycloak"]
    "db-api-password"         = ["api"]
    "db-pipeline-password"    = ["pipeline"]
    "db-backup-password"      = ["pipeline"]
    "db-keycloak-password"    = ["keycloak"]
    "keycloak-admin-password" = ["keycloak"]
    "receipt-signing-key"     = ["api"]
    "web-auth-secret"         = ["web"]
    "otlp-headers"            = ["api", "pipeline", "web"]
    "github-release-token"    = ["pipeline"]
  }

  secret_access = merge([
    for secret, readers in local.secrets : {
      for reader in readers : "${secret}/${reader}" => { secret = secret, reader = reader }
    }
  ]...)

  api_url = coalesce(var.api_base_url, google_cloud_run_v2_service.api.uri)

  # Cloud Run's deterministic URL, known before the service exists, so Keycloak can
  # be told its own hostname.
  keycloak_run_url = "https://keycloak-${local.env}-${data.google_project.this.number}.${var.region}.run.app"
  auth_url         = coalesce(var.auth_base_url, local.keycloak_run_url)
  issuer           = "${local.auth_url}/realms/${local.realm}"

  # A fixed audience avoids a cycle: the API can't reference its own URL.
  outbox_drain_audience = "${local.prefix}-outbox-drain"

  otel_env = {
    OTEL_EXPORTER_OTLP_ENDPOINT = var.otlp_endpoint
    OTEL_RESOURCE_ATTRIBUTES    = "deployment.environment=${local.env}"
  }
}

resource "terraform_data" "workspace_guard" {
  lifecycle {
    precondition {
      condition     = contains(["dev", "prod"], terraform.workspace)
      error_message = "Select the dev or prod workspace (tofu workspace select dev); the default workspace is not used."
    }
  }
}
