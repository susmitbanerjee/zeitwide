variable "project_id" {
  description = "GCP project that holds this environment."
  type        = string
}

variable "region" {
  description = "Region for every regional resource."
  type        = string
  default     = "us-central1"
}

variable "billing_account" {
  description = "Billing account ID (XXXXXX-XXXXXX-XXXXXX) that the budget watches."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$", var.billing_account))
    error_message = "billing_account must look like 012345-6789AB-CDEF01."
  }
}

variable "budget_amount" {
  description = "Monthly budget, in budget_currency; alerts fire at 50%, 90% and 100%."
  type        = number
  default     = 10
}

variable "budget_currency" {
  description = "Currency of the billing account; the Budgets API rejects any other."
  type        = string
  default     = "USD"

  validation {
    condition     = can(regex("^[A-Z]{3}$", var.budget_currency))
    error_message = "budget_currency is an ISO 4217 code such as USD or INR."
  }
}

variable "github_repository" {
  description = "GitHub repository that deploys this environment, as owner/name."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name."
  }
}

variable "github_repository_id" {
  description = "Numeric GitHub repository ID. The trust condition uses it because, unlike the name, it can't be reused after a rename or deletion."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+$", var.github_repository_id))
    error_message = "github_repository_id must be the numeric ID from `gh api repos/OWNER/NAME --jq .id`."
  }
}

variable "github_deploy_refs" {
  description = "Git refs whose workflows may deploy this environment."
  type        = list(string)
  default     = ["refs/heads/main"]
}

variable "web_origin" {
  description = "Public origin of the web app (Firebase Hosting site or custom domain), used for CORS and auth callbacks."
  type        = string

  validation {
    condition     = can(regex("^https://[^/]+$", var.web_origin))
    error_message = "web_origin must be an https origin with no path or trailing slash."
  }
}

variable "web_app_origin" {
  description = <<-EOT
    Origin for the signed-in web pages (/app, /admin, sign-in), served straight from Cloud Run
    because Firebase Hosting forwards no cookie but __session. Null uses the web service's
    run.app URL; set it once a domain such as app.example.com is mapped to the service.
  EOT
  type        = string
  default     = null

  validation {
    condition     = var.web_app_origin == null || can(regex("^https://[^/]+$", var.web_app_origin))
    error_message = "web_app_origin must be an https origin with no path or trailing slash."
  }
}

variable "first_party_api_key_ids" {
  description = "IDs (not secrets) of Zeitwide's own API keys, which get the first-party rate limit: the web app's key from create-web-api-key.sh."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for id in var.first_party_api_key_ids : can(regex("^[0-9a-f-]{36}$", id))])
    error_message = "first_party_api_key_ids are API key UUIDs."
  }
}

variable "api_domain" {
  description = "Domain to map straight to the API service, such as api.zeitwide.com. Null maps nothing; set api_base_url to match once DNS points at it."
  type        = string
  default     = null
}

variable "web_app_domain" {
  description = "Domain to map straight to the web service for signed-in pages, such as app.dev.example.com. Null maps nothing."
  type        = string
  default     = null
}

variable "api_base_url" {
  description = "Public base URL of the API once a custom domain is mapped. Null uses the run.app URL."
  type        = string
  default     = null
}

variable "auth_base_url" {
  description = "Public base URL of Keycloak once a custom domain is mapped. Null uses the service's deterministic run.app URL."
  type        = string
  default     = null
}

variable "google_client_id" {
  description = "Google OAuth client ID for Keycloak's identity provider."
  type        = string
  default     = ""
}

variable "microsoft_client_id" {
  description = "Microsoft Entra OAuth client ID for Keycloak's identity provider."
  type        = string
  default     = ""
}

variable "github_client_id" {
  description = "GitHub OAuth application client ID for Keycloak's identity provider."
  type        = string
  default     = ""
}

variable "receipt_key_id" {
  description = "Active Ed25519 receipt key ID, also present in receipt_public_keys_json."
  type        = string
  default     = ""
}

variable "receipt_public_keys_json" {
  description = "JSON public key ring; include old public keys until their receipts no longer need verification."
  type        = string
  default     = "[]"
}

variable "dataset_repository" {
  description = "Public GitHub repository, as owner/name, that receives a pull request for each dataset release."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.dataset_repository))
    error_message = "dataset_repository must be owner/name."
  }
}

variable "bot_contact_url" {
  description = "Page that explains Zeitwide's crawler and how to reach us; sent in the pipeline's User-Agent."
  type        = string

  validation {
    condition     = can(regex("^https://", var.bot_contact_url))
    error_message = "bot_contact_url must be an https URL."
  }
}

variable "otlp_endpoint" {
  description = "Grafana Cloud OTLP endpoint. The auth header is a secret, not a variable."
  type        = string
  default     = ""
}

variable "bootstrap_service_image" {
  description = "Image used only when a service is first created. CI deploys the real images and OpenTofu ignores image changes after that."
  type        = string
  default     = "us-docker.pkg.dev/cloudrun/container/hello"
}

variable "bootstrap_job_image" {
  description = "Image used only when the pipeline job is first created."
  type        = string
  default     = "us-docker.pkg.dev/cloudrun/container/job"
}

# The API's and web app's startup probes call their health endpoints, which the bootstrap
# image doesn't serve, so a service created from it never becomes ready. Pass the real
# images when a service or the job is (re)created, for example
# -var 'initial_images={api="…/api:TAG",web="…/web:TAG",keycloak="…",pipeline="…"}'.
variable "initial_images" {
  description = "Images by component (api, web, keycloak, pipeline), used only at creation; missing ones fall back to the bootstrap images."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for name in keys(var.initial_images) : contains(["api", "web", "keycloak", "pipeline"], name)])
    error_message = "initial_images keys are api, web, keycloak and pipeline."
  }
}
