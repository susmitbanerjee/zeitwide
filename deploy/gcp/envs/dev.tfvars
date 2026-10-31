# Not secret: IDs and public URLs only. Secret values go in Secret Manager.
project_id      = "project-6eaae4e0-3164-4e31-bc4"
billing_account = "01D9BB-5B0C19-F66CB0"

github_repository    = "susmitbanerjee/zeitwide"
github_repository_id = "1436841628"

receipt_key_id = "zw-2026-10"
receipt_public_keys_json = "[{\"key_id\":\"sc-2026-10\",\"public_key\":\"-----BEGIN PUBLIC KEY-----\\nMCowBQYDK2VwAyEAN591c3SENCQArrPMxqG6p9lOf/s39awzcUwcXSSxrgE=\\n-----END PUBLIC KEY-----\\n\",\"valid_from\":\"2026-10-28T09:28:06.69439Z\",\"valid_to\":\"2026-10-31T04:38:22Z\"},{\"key_id\":\"zw-2026-10\",\"public_key\":\"-----BEGIN PUBLIC KEY-----\\nMCowBQYDK2VwAyEAzAEoGKvCz40el8o02+sLdX6VOMot8YHcD+nN4KmlyHs=\\n-----END PUBLIC KEY-----\\n\",\"valid_from\":\"2026-10-31T04:38:22Z\",\"valid_to\":null}]"

web_origin         = "https://zeit-dev-6eaae4e0.web.app"
dataset_repository = "susmitbanerjee/zeit-dataset"
bot_contact_url    = "https://github.com/susmitbanerjee/zeit-dataset#crawler"

# The billing account is in INR: about USD 10 a month.
budget_currency = "INR"
budget_amount   = 900

# Allow deploys to dev from any branch's workflow by listing more refs here.
github_deploy_refs = ["refs/heads/main"]

# Set once custom domains are mapped (README, step 11).
# api_base_url  = "https://api.dev.example.com"
# auth_base_url = "https://auth.dev.example.com"

# otlp_endpoint = "https://otlp-gateway-prod-us-central-0.grafana.net/otlp"
