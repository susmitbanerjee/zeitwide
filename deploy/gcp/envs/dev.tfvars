# Not secret: IDs and public URLs only. Secret values go in Secret Manager.
project_id      = "project-6eaae4e0-3164-4e31-bc4"
billing_account = "01D9BB-5B0C19-F66CB0"

github_repository = "susmitbanerjee/Zeit"
# Still a placeholder: replace with `gh api repos/susmitbanerjee/Zeit --jq .id` before the
# full apply. Until then the GitHub Actions trust condition matches no repository.
github_repository_id = "0"

web_origin         = "https://zeit-dev-6eaae4e0.web.app"
dataset_repository = "susmitbanerjee/zeit-dataset"
bot_contact_url    = "https://github.com/susmitbanerjee/zeit-dataset#crawler"

# Allow deploys to dev from any branch's workflow by listing more refs here.
github_deploy_refs = ["refs/heads/main"]

# Set once custom domains are mapped (README, step 11).
# api_base_url  = "https://api.dev.example.com"
# auth_base_url = "https://auth.dev.example.com"

# otlp_endpoint = "https://otlp-gateway-prod-us-central-0.grafana.net/otlp"
