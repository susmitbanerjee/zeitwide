# The zone, on the Free plan. After the first apply, set the two name servers from the
# `name_servers` output at the registrar (README, step 4); nothing goes through Cloudflare before.
resource "cloudflare_zone" "this" {
  account = { id = var.account_id }
  name    = var.zone_name
  type    = "full"
}

locals {
  zone_id = cloudflare_zone.this.id

  # Full (strict): Cloudflare checks the origin's certificate. Cloud Run domain mappings (and
  # Firebase Hosting) hold a Google-managed certificate for the zone's own names.
  zone_settings = {
    ssl                      = "strict"
    always_use_https         = "on"
    min_tls_version          = "1.2"
    tls_1_3                  = "on"
    automatic_https_rewrites = "on"
  }
}

resource "cloudflare_zone_setting" "this" {
  for_each = local.zone_settings

  zone_id    = local.zone_id
  setting_id = each.key
  value      = each.value
}
