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
    api_user          = "zeitwide_api"
    pipeline_user     = "zeitwide_pipeline"
    backup_user       = "zeitwide_backup"
    api_pool_max      = 4
    pipeline_pool_max = 2
  }

  db_ca_mount_dir = "/secrets/db-ca"
  db_ca_file      = "${local.db_ca_mount_dir}/ca.pem"

  max_instances = {
    api = 2
    web = 2
  }

  # Runtime identities. Scheduler jobs get their own two accounts so the token that
  # can start the pipeline is never the one sent to the API.
  service_accounts = {
    api         = "Cloud Run service zeitwide-api"
    web         = "Cloud Run service zeitwide-web"
    pipeline    = "Cloud Run job zeitwide-pipeline"
    sched-run   = "Cloud Scheduler: starts the pipeline job"
    sched-drain = "Cloud Scheduler: calls the API outbox drain"
    deployer    = "GitHub Actions deploys via Workload Identity Federation"
  }

  # Secret containers only. Values are added by hand (README), never in code.
  # Each secret lists the runtime accounts that may read it.
  secrets = {
    "db-host"                       = ["api", "pipeline"]
    "db-port"                       = ["api", "pipeline"]
    "db-ca-cert"                    = ["api", "pipeline"]
    "db-api-password"               = ["api"]
    "db-pipeline-password"          = ["pipeline"]
    "db-backup-password"            = ["pipeline"]
    "google-oauth-client-secret"    = ["web"]
    "microsoft-oauth-client-secret" = ["web"]
    "github-oauth-client-secret"    = ["web"]
    "webhook-encryption-key"        = ["api"]
    "receipt-signing-key"           = ["api"]
    "web-auth-secret"               = ["web"]
    # Signs and checks the short-lived tokens the web app sends the API (add-secret.sh).
    "api-token-secret" = ["api", "web"]
    # The web app's own API key (create-web-api-key.sh), for prerendering answer pages.
    "web-api-key" = ["web"]
    # Grafana Cloud OTLP: the gateway URL, and the Authorization header for it.
    "otlp-endpoint" = ["api", "pipeline", "web"]
    "otlp-headers"  = ["api", "pipeline", "web"]
    # Grafana service account token: only the deploy workflow reads it (iam.tf), to apply
    # deploy/grafana. No runtime service gets it.
    "grafana-token"        = []
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

  # Public pages go through Firebase Hosting (web_origin); signed-in pages need cookies, so
  # they're served from the web service's own URL, known before the service exists. The names
  # must match the services in cloud_run.tf (zeitwide-web-dev, not zeitwide-dev-web): a wrong
  # host here sends every sign-in and /app link to a 404.
  web_run_url    = "https://${local.app}-web-${local.env}-${data.google_project.this.number}.${var.region}.run.app"
  web_app_origin = coalesce(var.web_app_origin, local.web_run_url)

  # A fixed audience avoids a cycle: the API can't reference its own URL.
  outbox_drain_audience = "${local.prefix}-outbox-drain"

  # The endpoint and its auth header come from Secret Manager (otel_secret_env). Every span is
  # recorded; each service keeps 10% of traces plus every trace with an error, decided by trace
  # ID so all three keep the same ones.
  otel_env = {
    # Grafana Cloud makes service.namespace/service.name the job label, so dashboards and
    # alerts can pick one environment: job="zeitwide-prod/zeitwide-api".
    OTEL_RESOURCE_ATTRIBUTES     = "service.namespace=${local.prefix},deployment.environment=${local.env}"
    OTEL_TRACES_SAMPLER          = "always_on"
    ZEITWIDE_TRACES_SAMPLE_RATIO = "0.1"
  }

  otel_secret_env = {
    OTEL_EXPORTER_OTLP_ENDPOINT = "otlp-endpoint"
    OTEL_EXPORTER_OTLP_HEADERS  = "otlp-headers"
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
