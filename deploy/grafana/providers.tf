# The token comes from GRAFANA_AUTH (a Grafana service account token), never from a file.
provider "grafana" {
  url = var.grafana_url
}
