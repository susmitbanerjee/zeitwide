locals {
  env    = terraform.workspace
  app    = "zeitwide"
  prefix = "${local.app}-${local.env}"

  labels = {
    app = local.app
    env = local.env
  }

  # Postgres runs on Aiven's free plan, which allows 20 connections. The per-client
  # pool caps below keep the worst case at 16 (README has the table).
  db = {
    zeitwide_database = "zeitwide"
    keycloak_database = "keycloak"
    api_user          = "zeitwide_api"
    pipeline_user     = "zeitwide_pipeline"
    backup_user       = "zeitwide_backup"
    keycloak_user     = "keycloak"
    api_pool_max      = 4
    pipeline_pool_max = 2
    keycloak_pool_max = 3
  }

  db_ca_mount_dir = "/secrets/db-ca"
  db_ca_file      = "${local.db_ca_mount_dir}/ca.pem"

  max_instances = {
    api      = 2
    web      = 2
    keycloak = 1
  }

  realm = "zeitwide"

  # Runtime identities. Scheduler jobs get their own two accounts so the token that
  # can start the pipeline is never the one sent to the API.
  service_accounts = {
    api         = "Cloud Run service zeitwide-api"
    web         = "Cloud Run service zeitwide-web"
    keycloak    = "Cloud Run service keycloak"
    pipeline    = "Cloud Run job zeitwide-pipeline"
    sched-run   = "Cloud Scheduler: starts the pipeline job"
    sched-drain = "Cloud Scheduler: calls the API outbox drain"
    deployer    = "GitHub Actions deploys via Workload Identity Federation"
  }

  # Secret containers only. Values are added by hand (README), never in code.
  # Each secret lists the runtime accounts that may read it.
  secrets = {
    "db-host"                       = ["api", "pipeline", "keycloak"]
    "db-port"                       = ["api", "pipeline", "keycloak"]
    "db-ca-cert"                    = ["api", "pipeline", "keycloak"]
    "db-api-password"               = ["api"]
    "db-pipeline-password"          = ["pipeline"]
    "db-backup-password"            = ["pipeline"]
    "db-keycloak-password"          = ["keycloak"]
    "keycloak-admin-password"       = ["keycloak"]
    "google-oauth-client-secret"    = ["keycloak"]
    "microsoft-oauth-client-secret" = ["keycloak"]
    "github-oauth-client-secret"    = ["keycloak"]
    "webhook-encryption-key"        = ["api"]
    "receipt-signing-key"           = ["api"]
    "web-auth-secret"               = ["web"]
    # The web app's own API key (create-web-api-key.sh), for prerendering answer pages.
    "web-api-key"          = ["web"]
    "otlp-headers"         = ["api", "pipeline", "web"]
    "github-release-token" = ["pipeline"]
  }

  secret_access = merge([
    for secret, readers in local.secrets : {
      for reader in readers : "${secret}/${reader}" => { secret = secret, reader = reader }
    }
  ]...)

  api_url = coalesce(var.api_base_url, google_cloud_run_v2_service.api.uri)
  api_public_url = coalesce(
    var.api_base_url,
    "https://${local.app}-api-${local.env}-${data.google_project.this.number}.${var.region}.run.app"
  )

  # Cloud Run's deterministic URL, known before the service exists, so Keycloak can
  # be told its own hostname.
  keycloak_run_url = "https://keycloak-${local.env}-${data.google_project.this.number}.${var.region}.run.app"
  auth_url         = coalesce(var.auth_base_url, local.keycloak_run_url)
  issuer           = "${local.auth_url}/realms/${local.realm}"

  # Public pages go through Firebase Hosting (web_origin); signed-in pages need cookies, so
  # they're served from the web service's own URL, known before the service exists. The names
  # must match the services in cloud_run.tf (zeitwide-web-dev, not zeitwide-dev-web): a wrong
  # host here sends every sign-in and /app link to a 404.
  web_run_url    = "https://${local.app}-web-${local.env}-${data.google_project.this.number}.${var.region}.run.app"
  web_app_origin = coalesce(var.web_app_origin, local.web_run_url)

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
