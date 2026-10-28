terraform {
  required_version = ">= 1.8"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
  }

  # Partial config: pass the state bucket at init time (see README).
  # State for each workspace lands at <prefix>/<workspace>.tfstate.
  backend "gcs" {}
}
