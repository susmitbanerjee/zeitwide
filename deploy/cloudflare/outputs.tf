output "name_servers" {
  description = "Set these at the registrar to move DNS to Cloudflare."
  value       = cloudflare_zone.this.name_servers
}

output "zone_id" {
  value = local.zone_id
}

output "ranges_namespace_id" {
  value = cloudflare_workers_kv_namespace.ranges.id
}
