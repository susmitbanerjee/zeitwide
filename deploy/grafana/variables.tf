variable "grafana_url" {
  description = "The Grafana Cloud stack."
  type        = string
  default     = "https://cleverhare3418.grafana.net"

  validation {
    condition     = can(regex("^https?://", var.grafana_url))
    error_message = "grafana_url must be an http(s) URL."
  }
}

variable "environments" {
  description = "Deployments the alerts watch (service.namespace zeitwide-<environment>). Dev and prod share the stack; the dashboards switch between them."
  type        = set(string)
  default     = ["dev"]

  validation {
    condition     = alltrue([for env in var.environments : contains(["dev", "prod"], env)])
    error_message = "environments may hold dev and prod only."
  }
}

variable "prometheus_data_source" {
  description = "Name of the stack's hosted Prometheus (Mimir) data source; Grafana Cloud creates it with the stack."
  type        = string
  default     = "grafanacloud-cleverhare3418-prom"
}

variable "alert_email" {
  description = "Where alerts are sent."
  type        = string
  default     = "susmit.b1@gmail.com"
}
