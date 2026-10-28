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

variable "budget_amount_usd" {
  description = "Monthly budget in USD; alerts fire at 50%, 90% and 100%."
  type        = number
  default     = 10
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

variable "dataset_repository" {
  description = "Public GitHub repository, as owner/name, that receives a pull request for each dataset release."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.dataset_repository))
    error_message = "dataset_repository must be owner/name."
  }
}

variable "bot_contact_url" {
  description = "Page that explains SettleCal's crawler and how to reach us; sent in the pipeline's User-Agent."
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
