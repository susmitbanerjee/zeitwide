variable "account_id" {
  description = "Cloudflare account ID (dashboard > account home > Account ID). Not secret."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.account_id))
    error_message = "account_id is the 32-character hex account ID."
  }
}

variable "zone_name" {
  description = "The site's apex domain."
  type        = string
}

variable "environment" {
  description = "The GCP environment behind this zone (deploy/gcp workspace): the env label in Grafana."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment is dev or prod."
  }
}

variable "web_origin_ipv4" {
  description = "A records for the apex: the addresses deploy/gcp's web domain mapping (or Firebase Hosting) asks for."
  type        = list(string)
}

variable "web_origin_ipv6" {
  description = "AAAA records for the apex, as above."
  type        = list(string)
  default     = []
}

variable "api_origin" {
  description = "CNAME target for api.<zone>: ghs.googlehosted.com for a Cloud Run domain mapping."
  type        = string
  default     = "ghs.googlehosted.com"
}

variable "other_records" {
  description = "Records copied from the previous DNS host (verification TXT, DMARC, mail), never proxied."
  type = map(object({
    type     = string
    name     = string
    content  = string
    priority = optional(number)
  }))
  default = {}
}

variable "loki_push_url" {
  description = "Grafana Cloud Loki push URL (stack > Loki > Details), ending /loki/api/v1/push. Empty leaves the nightly report unconfigured (its cron then fails, visibly)."
  type        = string
  default     = ""

  validation {
    condition     = var.loki_push_url == "" || can(regex("^https://.+/loki/api/v1/push$", var.loki_push_url))
    error_message = "loki_push_url is an https URL ending /loki/api/v1/push."
  }
}

variable "loki_user" {
  description = "Grafana Cloud Loki instance (user) ID: a number, not secret."
  type        = string
  default     = ""
}

variable "worker_bundle" {
  description = "The built Worker, relative to this folder (cd edge && npm run build)."
  type        = string
  default     = "edge/dist/index.js"
}
