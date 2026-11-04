# Proxied: visitors reach Cloudflare, and Cloudflare reaches the Google front end with the same
# Host and SNI, so the Cloud Run domain mappings keep working unchanged.
resource "cloudflare_dns_record" "web_ipv4" {
  for_each = toset(var.web_origin_ipv4)

  zone_id = local.zone_id
  name    = var.zone_name
  type    = "A"
  content = each.value
  proxied = true
  ttl     = 1
  comment = "Web origin (deploy/cloudflare)"
}

resource "cloudflare_dns_record" "web_ipv6" {
  for_each = toset(var.web_origin_ipv6)

  zone_id = local.zone_id
  name    = var.zone_name
  type    = "AAAA"
  content = each.value
  proxied = true
  ttl     = 1
  comment = "Web origin (deploy/cloudflare)"
}

# No Worker route on api.<zone>: API calls only pass through Cloudflare's proxy. Before this is
# proxied, deploy/gcp needs api_trusted_proxies = 2 (README, step 6).
resource "cloudflare_dns_record" "api" {
  zone_id = local.zone_id
  name    = "api.${var.zone_name}"
  type    = "CNAME"
  content = var.api_origin
  proxied = true
  ttl     = 1
  comment = "API origin (deploy/cloudflare)"
}

resource "cloudflare_dns_record" "other" {
  for_each = var.other_records

  zone_id  = local.zone_id
  name     = each.value.name
  type     = each.value.type
  content  = each.value.content
  priority = each.value.priority
  proxied  = false
  ttl      = 3600
  comment  = "Copied from the previous DNS host: ${each.key}"
}
