terraform {
  required_version = ">= 1.8"

  required_providers {
    grafana = {
      source  = "grafana/grafana"
      version = "~> 4.52"
    }
  }

  # Partial config, like deploy/gcp: pass the state bucket at init time (README).
  backend "gcs" {}
}
