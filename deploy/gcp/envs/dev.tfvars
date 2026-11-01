# Not secret: IDs and public URLs only. Secret values go in Secret Manager.
project_id      = "project-6eaae4e0-3164-4e31-bc4"
billing_account = "01D9BB-5B0C19-F66CB0"

github_repository    = "susmitbanerjee/zeitwide"
github_repository_id = "1436841628"

receipt_key_id           = "zw-2026-10"
receipt_public_keys_json = "[{\"key_id\":\"sc-2026-10\",\"public_key\":\"-----BEGIN PUBLIC KEY-----\\nMCowBQYDK2VwAyEAN591c3SENCQArrPMxqG6p9lOf/s39awzcUwcXSSxrgE=\\n-----END PUBLIC KEY-----\\n\",\"valid_from\":\"2026-10-28T09:28:06.69439Z\",\"valid_to\":\"2026-10-31T04:38:22Z\"},{\"key_id\":\"zw-2026-10\",\"public_key\":\"-----BEGIN PUBLIC KEY-----\\nMCowBQYDK2VwAyEAzAEoGKvCz40el8o02+sLdX6VOMot8YHcD+nN4KmlyHs=\\n-----END PUBLIC KEY-----\\n\",\"valid_from\":\"2026-10-31T04:38:22Z\",\"valid_to\":null}]"

# One host for everything: Cloud Run serves zeitwide.com directly (domain mapping), because
# Firebase Hosting would strip the sign-in cookies. api.zeitwide.com maps to the API service.
web_origin     = "https://zeitwide.com"
web_app_origin = "https://zeitwide.com"
web_app_domain = "zeitwide.com"
api_domain     = "api.zeitwide.com"

# The web app's own key (create-web-api-key.sh prints the ID), on the first-party limit.
first_party_api_key_ids = ["a324246f-affc-4c94-a7d1-5adcb20456e8"]

dataset_repository = "susmitbanerjee/zeit-dataset"
bot_contact_url    = "https://github.com/susmitbanerjee/zeit-dataset#crawler"

# The billing account is in INR: about USD 10 a month.
budget_currency = "INR"
budget_amount   = 900

# Allow deploys to dev from any branch's workflow by listing more refs here.
github_deploy_refs = ["refs/heads/main"]

# Set once custom domains are mapped (README, step 11).
api_base_url = "https://api.zeitwide.com"
# auth_base_url = "https://auth.dev.example.com"

# otlp_endpoint = "https://otlp-gateway-prod-us-central-0.grafana.net/otlp"

# OAuth client IDs for sign-in (public; the secrets are in Secret Manager). Callback URLs:
# https://zeitwide.com/api/auth/callback/google and /microsoft. A provider with no client ID
# gets no button, so GitHub stays hidden until github_client_id is set.
google_client_id    = "18853622142-q9lb6915a8pldfqt4arhib3einmkkvba.apps.googleusercontent.com"
microsoft_client_id = "89e130c0-2fa4-426a-b847-10fba84277c0"

# Who may review changes in the admin screens: a verified Google or GitHub address.
reviewer_emails = ["susmit.b1@gmail.com"]
