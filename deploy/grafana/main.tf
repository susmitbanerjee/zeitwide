locals {
  # Grafana Cloud turns service.namespace/service.name into the job label (deploy/gcp/locals.tf).
  jobs = {
    for env in var.environments : env => {
      api      = "zeitwide-${env}/zeitwide-api"
      pipeline = "zeitwide-${env}/zeitwide-pipeline"
    }
  }
}

data "grafana_data_source" "prometheus" {
  name = var.prometheus_data_source
}

resource "grafana_folder" "zeitwide" {
  title = "Zeitwide"
}

# Sources, API and Adoption, once for the stack: each picks its data source and environment
# with dashboard variables.
resource "grafana_dashboard" "this" {
  for_each = fileset("${path.module}/dashboards", "*.json")

  folder      = grafana_folder.zeitwide.uid
  config_json = file("${path.module}/dashboards/${each.value}")
  overwrite   = true
}
