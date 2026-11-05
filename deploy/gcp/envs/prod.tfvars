# Not secret: IDs and public URLs only. Secret values go in Secret Manager.
project_id           = "GCP_PROJECT_ID"
billing_account      = "000000-000000-000000"
github_repository    = "OWNER/zeitwide"
github_repository_id = "0"
web_origin           = "https://zeitwide.web.app"
dataset_repository   = "OWNER/zeitwide-dataset"
open_data_repository = "susmitbanerjee/zeitwide-data"
bot_contact_url      = "https://github.com/OWNER/zeitwide#crawler"

github_deploy_refs = ["refs/heads/main"]

# Set once custom domains are mapped (README, step 11).
# api_base_url  = "https://api.example.com"
# auth_base_url = "https://auth.example.com"

# Grafana Cloud > Frontend Observability > your app > Web SDK config. The OTLP endpoint and
# token are secrets (otlp-endpoint, otlp-headers), not variables.
# faro_url = "https://faro-collector-prod-us-central-0.grafana.net/collect/<app key>"
